package com.olegych.scastie
package client

import api._

import upickle.default.{read => uread, ReadWriter, macroRW => upickleMacroRW}

object AppState {
  def default = AppState(
    view = View.Editor,
    running = false,
    eventSource = None,
    websocket = None,
    isShowingHelpAtStartup = true,
    isHelpModalClosed = false,
    isDarkTheme = true,
    consoleIsOpen = false,
    consoleHasUserOutput = false,
    inputsHasChanged = false,
    snippetId = None,
    loadSnippet = true,
    isStartup = true,
    loadScalaJsScript = false,
    user = None,
    attachedDoms = Map(),
    inputs = Inputs.default,
    outputs = Outputs.default
  )

  implicit val dontSerializeAttachedDoms: ReadWriter[AttachedDoms] = dontSerializeMap[String, HTMLElement]
  implicit val dontSerializeWebSocket: ReadWriter[Option[WebSocket]] = dontSerializeOption[WebSocket]
  implicit val dontSerializeEventSource: ReadWriter[Option[EventSource]] = dontSerializeOption[EventSource]
  implicit val pkl: ReadWriter[AppState] = upickleMacroRW[State]
}

case class AppState(
    view: View,
    running: Boolean,
    eventSource: Option[EventSource],
    websocket: Option[WebSocket],
    isShowingHelpAtStartup: Boolean,
    isHelpModalClosed: Boolean,
    isDarkTheme: Boolean,
    consoleIsOpen: Boolean,
    consoleHasUserOutput: Boolean,
    inputsHasChanged: Boolean,
    snippetId: Option[SnippetId],
    loadSnippet: Boolean,
    isStartup: Boolean,
    loadScalaJsScript: Boolean,
    user: Option[User],
    attachedDoms: AttachedDoms,
    inputs: Inputs,
    outputs: Outputs
  ) {
  def copyAndSave(
    view: View = view,
    running: Boolean = running,
    eventSource: Option[EventSource] = eventSource,
    websocket: Option[WebSocket] = websocket,
    isShowingHelpAtStartup: Boolean = isShowingHelpAtStartup,
    isHelpModalClosed: Boolean = isHelpModalClosed,
    isDarkTheme: Boolean = isDarkTheme,
    consoleIsOpen: Boolean = consoleIsOpen,
    consoleHasUserOutput: Boolean = consoleHasUserOutput,
    inputsHasChanged: Boolean = inputsHasChanged,
    snippetId: Option[SnippetId] = snippetId,
    user: Option[User] = user,
    attachedDoms: AttachedDoms = attachedDoms,
    inputs: Inputs = inputs,
    outputs: Outputs = outputs): AppState = {

    val state0 =
      copy(
        view,
        running,
        eventSource,
        websocket,
        isShowingHelpAtStartup,
        isHelpModalClosed,
        isDarkTheme,
        consoleIsOpen,
        consoleHasUserOutput,
        inputsHasChanged,
        snippetId,
        loadSnippet,
        isStartup,
        loadScalaJsScript,
        user,
        attachedDoms,
        inputs.copy(
          showInUserProfile = false,
          forked = None
        ),
        outputs
      )

    val state1 = 
      if(inputs.target.targetType == ScalaTargetType.JS) {
        // we need to re-evaluate the javascript
      }
      else state0

    LocalStorage.save(state0)

    state0
  }

  def isClearable: Boolean =
    outputs.isClearable

  def setRunning(running: Boolean): AppState = {
    val console = !running && !consoleHasUserOutput
    copyAndSave(running = running, consoleIsOpen = !console)
  }

  def toggleTheme: AppState =
    copyAndSave(isDarkTheme = !isDarkTheme)

  def toggleConsole: AppState =
    copyAndSave(consoleIsOpen = !consoleIsOpen)

  def toggleWorksheetMode: AppState =
    copyAndSave(
      inputs = inputs.copy(worksheetMode = !inputs.worksheetMode),
      inputsHasChanged = true
    )

  def toggleHelpAtStartup: AppState =
    copyAndSave(isShowingHelpAtStartup = !isShowingHelpAtStartup)

  def closeHelp: AppState = 
    resetOutputs
      .copyAndSave(isHelpModalClosed = true)
      .copy(isStartup = false)
  
  def showHelp: AppState = copy(isHelpModalClosed = false)
  
  def openConsole: AppState =
    copyAndSave(consoleIsOpen = true)

  def setUserOutput: AppState =
    copyAndSave(consoleHasUserOutput = true)

  def log(lines: Seq[String]): AppState = ???
    // copyAndSave(outputs = outputs.copy(console = outputs.console ++ lines))

  def log(line: String): AppState = ???
    // log(Seq(line))

  def log(line: Option[String]): AppState = ???
    // line match {
    //   case Some(l) => log(l + "\n")
    //   case None => this
    // }

  def setLoadSnippet(value: Boolean): AppState = 
    copy(loadSnippet = value)

  def setUser(user: Option[User]): AppState =
    copyAndSave(user = user)

  def setCode(code: String): AppState =
    copyAndSave(
      inputs = inputs.copy(code = code),
      inputsHasChanged = true
    )

  def setInputs(inputs: Inputs): AppState =
    copyAndSave(
      inputs = inputs
    )

  def setSbtConfigExtra(config: String): AppState =
    copyAndSave(
      inputs = inputs.copy(sbtConfigExtra = config),
      inputsHasChanged = true
    )

  def setCleanInputs: AppState =
    copyAndSave(inputsHasChanged = false)

  def setView(newView: View): AppState =
    copyAndSave(view = newView)

  def setTarget(target: ScalaTarget): AppState =
    copyAndSave(
      inputs = inputs.copy(target = target),
      inputsHasChanged = true
    )

  def addScalaDependency(scalaDependency: ScalaDependency,
                         project: Project): AppState =
    copyAndSave(
      inputs = inputs.addScalaDependency(scalaDependency, project),
      inputsHasChanged = true
    )

  def removeScalaDependency(scalaDependency: ScalaDependency): AppState =
    copyAndSave(
      inputs = inputs.removeScalaDependency(scalaDependency),
      inputsHasChanged = true
    )

  def updateDependencyVersion(scalaDependency: ScalaDependency,
                              version: String) = {
    val newScalaDependency = scalaDependency.copy(version = version)
    copyAndSave(
      inputs = inputs.copy(
        libraries = (inputs.libraries - scalaDependency) + newScalaDependency
      ),
      inputsHasChanged = true
    )
  }

  def resetOutputs =
    copyAndSave(outputs = Outputs.default,
                consoleIsOpen = false,
                consoleHasUserOutput = false,
                attachedDoms = Map())

  def setRuntimeError(runtimeError: Option[RuntimeError]) =
    if (runtimeError.isEmpty) this
    else copyAndSave(outputs = outputs.copy(runtimeError = runtimeError))

  def addProgress(progress: SnippetProgress) = {
    val state =
      addOutputs(progress.compilationInfos, progress.instrumentations)
        .log(progress.userOutput)
        .log(progress.sbtOutput)
        .setForcedProgramMode(progress.forcedProgramMode)
        .setRunning(!progress.done)
        .setLoadScalaJsScript(loadScalaJsScript | progress.done)
        .setRuntimeError(progress.runtimeError)

    if (!progress.userOutput.isEmpty) state.setUserOutput
    else state
  }

  def setProgresses(progresses: List[SnippetProgress]) = {
    progresses.foldLeft(this) {
      case (state, progress) => state.addProgress(progress)
    }
  }

  def setSnippetId(snippetId: SnippetId) = {
    copyAndSave(snippetId = Some(snippetId))
  }

  private def info(message: String) = Problem(api.Info, None, message)

  def setForcedProgramMode(forcedProgramMode: Boolean) = {
    if (!forcedProgramMode) this
    else {
      copyAndSave(
        outputs = outputs.copy(
          compilationInfos = outputs.compilationInfos +
              info("You don't need a main method (or extends App) in Worksheet Mode")
        ))
    }
  }

  def setLoadScalaJsScript(value: Boolean) = {s
    copy(loadScalaJsScript = value)
  }

  def addOutputs(compilationInfos: List[api.Problem],
                 instrumentations: List[api.Instrumentation]) = {

    def topDef(problem: api.Problem): Boolean = {
      problem.severity == api.Error &&
      problem.message == "expected class or object definition"
    }

    val useWorksheetModeTip =
      if (compilationInfos.exists(ci => topDef(ci)))
        Set(
          info("""|It seems you're writing code without an enclosing class/object. 
                  |Switch to Worksheet mode if you want to use scastie more like a REPL.""".stripMargin))
      else Set()

    copyAndSave(
      outputs = outputs.copy(
        compilationInfos = outputs.compilationInfos ++ compilationInfos.toSet ++ useWorksheetModeTip,
        instrumentations = outputs.instrumentations ++ instrumentations.toSet
      ))
  }
}
