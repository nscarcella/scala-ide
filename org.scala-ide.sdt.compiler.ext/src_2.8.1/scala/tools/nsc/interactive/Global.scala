package scala.tools.nsc
package interactive


import java.io.{ PrintWriter, StringWriter , FileReader, FileWriter }
import _root_.scala.collection.mutable.{ArrayBuffer, ListBuffer, SynchronizedBuffer, HashMap}

//BACK-2.8.0 use absolute import to avoid wrong search with relative
import _root_.scala.collection.mutable
import mutable.{LinkedHashMap, SynchronizedMap,LinkedHashSet, SynchronizedSet}
import _root_.scala.concurrent.SyncVar
import _root_.scala.util.control.ControlThrowable
import _root_.scala.tools.nsc.io.AbstractFile
import _root_.scala.tools.nsc.util.{SourceFile, BatchSourceFile, Position, RangePosition, NoPosition}
import _root_.scala.tools.nsc.reporters._
import _root_.scala.tools.nsc.symtab._
import _root_.scala.tools.nsc.ast._
import scala.tools.nsc.interactive.io.{Pickler, LogReplay, Logger, NullLogger, Replayer }
import scala.tools.nsc.interactive.util.{MultiHashMap }

/** The main class of the presentation compiler in an interactive environment such as an IDE
 */
class Global(settings: Settings, reporter: Reporter) 
  extends _root_.scala.tools.nsc.Global(settings, reporter) 
     with CompilerControl 
     with RangePositions
     with ContextTrees 
     with RichCompilationUnits 
     with Picklers { 
self =>

  import definitions._

  val debugIDE = false
  //BACK-2.8 verboseIDE
  var verboseIDE = true

  private def replayName = "" //BACK-2.8 settings.YpresentationReplay.value
  private def logName = "" //BACK-2.8 settings.YpresentationLog.value
  private def afterTypeDelay = 300 //BACK-2.8 settings.YpresentationDelay.value
  private final val SleepTime = 10

  val log =
    if (replayName != "") new Replayer(new FileReader(replayName))
    else if (logName != "") new Logger(new FileWriter(logName))
    else NullLogger

  import log.logreplay
  debugLog("interactive compiler from 9 Feb")
  debugLog("logger: " + log.getClass + " writing to " + (new java.io.File(logName)).getAbsolutePath)
  debugLog("classpath: "+classPath)  
  /** Print msg only when debugIDE is true. */
  @inline final def debugLog(msg: => String) = 
    if (debugIDE) println(msg)

  /** Inform with msg only when verboseIDE is true. */
  @inline final def informIDE(msg: => String) = 
    if (verboseIDE) println("["+msg+"]")
  
  //BACK-2.8 (override notthing)
  //override def forInteractive = true
  def forInteractive = true
  override def onlyPresentation = true

  /** A map of all loaded files to the rich compilation units that correspond to them.
   */ 
  val unitOfFile = new LinkedHashMap[AbstractFile, RichCompilationUnit] with
                       SynchronizedMap[AbstractFile, RichCompilationUnit]

  /** A list containing all those files that need to be removed
   *  Units are removed by getUnit, typically once a unit is finished compiled.
   */
  protected val toBeRemoved = new ArrayBuffer[AbstractFile] with SynchronizedBuffer[AbstractFile]
  
  /** A map that associates with each abstract file the set of responses that are waiting
   *  (via waitLoadedTyped) for the unit associated with the abstract file to be loaded and completely typechecked.
   */
  protected val waitLoadedTypeResponses = new MultiHashMap[SourceFile, Response[Tree]]
 
  /** A map that associates with each abstract file the set of responses that ware waiting
   *  (via build) for the unit associated with the abstract file to be parsed and entered
   */
  protected var getParsedEnteredResponses = new MultiHashMap[SourceFile, Response[Tree]]

  /** The compilation unit corresponding to a source file
   *  if it does not yet exist create a new one atomically
   *  Note: We want to remove this.
   */
  protected[interactive] def getOrCreateUnitOf(source: SourceFile): RichCompilationUnit =
    unitOfFile.getOrElse(source.file, { println("precondition violated: "+source+" is not loaded"); new Exception().printStackTrace(); new RichCompilationUnit(source) })

  /** Work through toBeRemoved list to remove any units.
   *  Then return optionlly unit associated with given source.
   */
  protected[interactive] def getUnit(s: SourceFile): Option[RichCompilationUnit] = {
    toBeRemoved.synchronized {
      for (f <- toBeRemoved) {
        unitOfFile -= f
        allSources = allSources filter (_.file != f)
      }
      toBeRemoved.clear()
    }
    unitOfFile get s.file
  }
  /** A list giving all files to be typechecked in the order they should be checked.
   */
  protected var allSources: List[SourceFile] = List()

  //BACK-2.8 set currentTyperRun to protected scope to allowing set it in newTyperRun override
  /** The currently active typer run */
  //private var currentTyperRun: TyperRun = _
  protected var currentTyperRun: TyperRun = _
  newTyperRun()

  /** Is a background compiler run needed?
   *  Note: outOfDate is true as long as there is a background compile scheduled or going on.
   */
  protected[interactive] var outOfDate = false

  /** Units compiled by a run with id >= minRunId are considered up-to-date  */
  protected[interactive] var minRunId = 1
  
  private var interruptsEnabled = true 

  private val NoResponse: Response[_] = new Response[Any]

  /** The response that is currently pending, i.e. the compiler
   *  is working on providing an asnwer for it.
   */
  private var pendingResponse: Response[_] = NoResponse 

  // ----------- Overriding hooks in nsc.Global -----------------------
  
  /** Called from typechecker, which signals hereby that a node has been completely typechecked.
   *  If the node includes unit.targetPos, abandons run and returns newly attributed tree.
   *  Otherwise, if there's some higher priority work to be done, also abandons run with a FreshRunReq.
   *  @param  context  The context that typechecked the node
   *  @param  old      The original node
   *  @param  result   The transformed node
   */
  override def signalDone(context: Context, old: Tree, result: Tree) {
    def integrateNew() {
      //Defensive
      if ((context ne null) && (context.unit ne null)) {
        // Don't think this is needed anymore, let's see if we can remove
        context.unit.body = new TreeReplacer(old, result) transform context.unit.body
      }
    }
    if (interruptsEnabled){  // BACK-2.8 lockedCount not found ... && analyzer.lockedCount == 0) {
      if (context.unit != null && 
          result.pos.isOpaqueRange && 
          (result.pos includes context.unit.targetPos)) {
        integrateNew()
        var located = new TypedLocator(context.unit.targetPos) locateIn result
        if (located == EmptyTree) {
          println("something's wrong: no "+context.unit+" in "+result+result.pos)
          located = result
        }
        throw new TyperResult(located)
      }
      val typerRun = currentTyperRun
      
      while(true) 
        try {
          try {
            pollForWork(old.pos)
      } catch {
            case ex : Throwable =>
          if (context.unit != null) integrateNew()
              log.flush()
              throw ex
      }
          if (typerRun == currentTyperRun)
            return
         
          integrateNew()
          throw FreshRunReq
        } catch {
          case ex : ValidateException => // Ignore, this will have been reported elsewhere
            debugLog("validate exception caught: "+ex)
        }
    }
  }

  /** Called from typechecker every time a context is created.
   *  Registers the context in a context tree
   */
  override def registerContext(c: Context) = c.unit match {
    case u: RichCompilationUnit => addContext(u.contexts, c)
    case _ =>
  }

  /** The top level classes and objects currently seen in the presentation compiler
   */
  private val currentTopLevelSyms = new mutable.LinkedHashSet[Symbol]

  /** The top level classes and objects no longer seen in the presentation compiler
   */
  val deletedTopLevelSyms = new mutable.LinkedHashSet[Symbol] with mutable.SynchronizedSet[Symbol]

  /** Called from typechecker every time a top-level class or object is entered.
   */
  override def registerTopLevelSym(sym: Symbol) { currentTopLevelSyms += sym }
  
//BACK 2.8
//  /** Symbol loaders in the IDE parse all source files loaded from a package for
//   *  top-level idents. Therefore, we can detect top-level symbols that have a name
//   *  different from their source file
//   */
//  override lazy val loaders = new BrowsingLoaders {
//    val global: Global.this.type = Global.this
//  }

  // ----------------- Polling ---------------------------------------
  
  case class WorkEvent(atNode: Int, atMillis: Long)

  private var moreWorkAtNode: Int = -1
  private var nodesSeen = 0
  private var lastWasReload = false
  
  /** The number of pollForWorks after which the presentation compiler yields.
   *  Yielding improves responsiveness on systems with few cores because it
   *  gives the UI thread a chance to get new tasks and interrupt the presentation
   *  compiler with them.
   */
  private final val yieldPeriod = 8

  /** Called from runner thread and signalDone:
   *  Poll for interrupts and execute them immediately.
   *  Then, poll for exceptions and execute them. 
   *  Then, poll for work reload/typedTreeAt/doFirst commands during background checking.
   *  @param pos   The position of the tree if polling while typechecking, NoPosition otherwise
   *  
   */
  protected[interactive] def pollForWork(pos: Position) {
    if (pos == NoPosition || nodesSeen % yieldPeriod == 0)
      Thread.`yield`()
    
    def nodeWithWork(): Option[WorkEvent] =
      if (scheduler.moreWork || pendingResponse.isCancelled) Some(new WorkEvent(nodesSeen, System.currentTimeMillis))
      else None

    nodesSeen += 1
    logreplay("atnode", nodeWithWork()) match {
      case Some(WorkEvent(id, _)) => 
        debugLog("some work at node "+id+" current = "+nodesSeen)
//        assert(id >= nodesSeen) 
        moreWorkAtNode = id
      case None =>
    }

    if (nodesSeen >= moreWorkAtNode) {
      
      logreplay("asked", scheduler.pollInterrupt()) match {
        case Some(ir) =>
          try {
            interruptsEnabled = false
            ir.execute()
          } finally {
            interruptsEnabled = true
          }
          pollForWork(pos)
        case _ =>
      }
     
      if (logreplay("cancelled", pendingResponse.isCancelled)) { 
        throw CancelException
      }
    
      logreplay("exception thrown", scheduler.pollThrowable()) match {
        case Some(ex @ FreshRunReq) => 
          newTyperRun()
          minRunId = currentRunId
          if (outOfDate) throw ex
          else outOfDate = true
        case Some(ex: Throwable) => log.flush(); throw ex
        case _ =>
      }
     
      lastWasReload = false
    
      logreplay("workitem", scheduler.nextWorkItem()) match {
        case Some(action) =>
          try {
            debugLog("picked up work item at "+pos+": "+action)
            action()
            debugLog("done with work item: "+action)
          } finally {
            debugLog("quitting work item: "+action)
          }
        case None =>
      }
    }
  }    

  def debugInfo(source : SourceFile, start : Int, length : Int): String = {
    println("DEBUG INFO "+source+"/"+start+"/"+length)
    val end = start+length
    val pos = rangePos(source, start, start, end)

    val tree = locateTree(pos)
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    newTreePrinter(pw).print(tree)
    pw.flush
    
    val typed = new Response[Tree]
    askTypeAt(pos, typed)
    val typ = typed.get.left.toOption match {
      case Some(tree) =>
        val sw = new StringWriter
        val pw = new PrintWriter(sw)
        newTreePrinter(pw).print(tree)
        pw.flush
        sw.toString
      case None => "<None>"      
    }

    val completionResponse = new Response[List[Member]]
    askTypeCompletion(pos, completionResponse)
    val completion = completionResponse.get.left.toOption match {
      case Some(members) =>
        members mkString "\n"
      case None => "<None>"      
    }
    
    source.content.view.drop(start).take(length).mkString+" : "+source.path+" ("+start+", "+end+
    ")\n\nlocateTree:\n"+sw.toString+"\n\naskTypeAt:\n"+typ+"\n\ncompletion:\n"+completion
  }

  // ----------------- The Background Runner Thread -----------------------

  private var threadId = 0

  /** The current presentation compiler runner */
  @volatile protected[interactive] var compileRunner = newRunnerThread()

  /** Create a new presentation compiler runner.
   */
  protected[interactive] def newRunnerThread(): Thread = {
    threadId += 1
    compileRunner = new PresentationCompilerThread(this, threadId)
    compileRunner.start()
    compileRunner
  }

  /** Compile all loaded source files in the order given by `allSources`.
   */ 
  protected[interactive] def backgroundCompile() {
    informIDE("Starting new presentation compiler type checking pass")
    //BACK-2.8 reporter.reset has no parenthesis
    reporter.reset
    // remove any files in first that are no longer maintained by presentation compiler (i.e. closed)
    allSources = allSources filter (s => unitOfFile contains (s.file))

    for (s <- allSources; unit <- getUnit(s)) {
      pollForWork(NoPosition)
      if (!unit.isUpToDate && unit.status != JustParsed) reset(unit) // reparse previously typechecked units.
      parseAndEnter(unit)
    }
    
    /** Sleep window */
    if (afterTypeDelay > 0 && lastWasReload) {
      val limit = System.currentTimeMillis() + afterTypeDelay
      while (System.currentTimeMillis() < limit) {
        Thread.sleep(SleepTime)
        pollForWork(NoPosition)
      }
    }

    for (s <- allSources; unit <- getUnit(s)) {
      if (!unit.isUpToDate) typeCheck(unit)
      else debugLog("already up to date: "+unit)
      for (r <- waitLoadedTypeResponses(unit.source))
        r set unit.body
    }

    informIDE("Everything is now up to date")
    
    for ((source, rs) <- waitLoadedTypeResponses; r <- rs) r raise new NoSuchUnitError(source.file)
    waitLoadedTypeResponses.clear()
    
    var atOldRun = true
    for ((source, rs) <- getParsedEnteredResponses; r <- rs) {
      if (atOldRun) { newTyperRun(); atOldRun = false }
      getParsedEnteredNow(source, r)  
    }
    getParsedEnteredResponses.clear()
  }

  /** Reset unit to unloaded state */
  protected def reset(unit: RichCompilationUnit): Unit = {
    unit.depends.clear()
    unit.defined.clear()
    unit.synthetics.clear()
    unit.toCheck.clear()
    unit.targetPos = NoPosition
    unit.contexts.clear()
    unit.problems.clear()
    unit.body = EmptyTree
    unit.status = NotLoaded
  }

  /** Parse unit and create a name index, unless this has already been done before */
  protected def parseAndEnter(unit: RichCompilationUnit): Unit = 
    if (unit.status == NotLoaded) {
    debugLog("parsing: "+unit)
    currentTyperRun.compileLate(unit)
    if (debugIDE && !reporter.hasErrors) validatePositions(unit.body)
    if (!unit.isJava) syncTopLevelSyms(unit)
    unit.status = JustParsed
  }

  /** Make sure unit is typechecked
   */
  protected def typeCheck(unit: RichCompilationUnit) {
    debugLog("type checking: "+unit)
    parseAndEnter(unit)
    unit.status = PartiallyChecked
    currentTyperRun.typeCheck(unit)
    unit.lastBody = unit.body
    unit.status = currentRunId
  }

  /** Update deleted and current top-level symbols sets */
  def syncTopLevelSyms(unit: RichCompilationUnit) {
    val deleted = currentTopLevelSyms filter { sym =>
      /** We sync after namer phase and it resets all the top-level symbols
       *  that survive the new parsing
       *  round to NoPeriod.
       */
      sym.sourceFile == unit.source.file && 
      sym.validTo != NoPeriod && 
      runId(sym.validTo) < currentRunId 
    }
    for (d <- deleted) {
      d.owner.info.decls unlink d
      deletedTopLevelSyms += d
      currentTopLevelSyms -= d
    }
  }
      
  /** Move list of files to front of allSources */
  def moveToFront(fs: List[SourceFile]) {
    allSources = fs ::: (allSources diff fs)
  }

  // ----------------- Implementations of client commands -----------------------
  
  def respond[T](result: Response[T])(op: => T): Unit = 
    respondGradually(result)(Stream(op))

  def respondGradually[T](response: Response[T])(op: => Stream[T]): Unit = {
    val prevResponse = pendingResponse
    try {
      pendingResponse = response
      if (!response.isCancelled) {
        var results = op
        while (!response.isCancelled && results.nonEmpty) {
          val result = results.head
          results = results.tail
          if (results.isEmpty) response set result
          else response setProvisionally result
        }
      }
    } catch {
      case CancelException =>
        debugLog("cancelled")
/* Commented out. Typing should always cancel requests 
      case ex @ FreshRunReq =>
        scheduler.postWorkItem(() => respondGradually(response)(op))
        throw ex
*/
      case ex =>
        if (debugIDE) {
          println("exception thrown during response: "+ex)
          ex.printStackTrace()
        }
        response raise ex
    } finally {
      pendingResponse = prevResponse
    }
  }

  protected def reloadSource(source: SourceFile) {
    val unit = new RichCompilationUnit(source)
    unitOfFile(source.file) = unit
    reset(unit)
    //parseAndEnter(unit)
  }
  /** Make sure a set of compilation units is loaded and parsed */
  protected def reloadSources(sources: List[SourceFile]) {
    newTyperRun()
    minRunId = currentRunId
    sources foreach reloadSource
    moveToFront(sources)
  }

  /** Make sure a set of compilation units is loaded and parsed */
  protected def reload(sources: List[SourceFile], response: Response[Unit]) {
    informIDE("reload: " + sources)
    lastWasReload = true
    respond(response)(reloadSources(sources))
    if (outOfDate) throw FreshRunReq // cancel background compile
    else outOfDate = true            // proceed normally and enable new background compile
  }

  /** A fully attributed tree located at position `pos`  */
  protected def typedTreeAt(pos: Position): Tree = getUnit(pos.source) match {
    case None =>
      reloadSources(List(pos.source))
      val result = typedTreeAt(pos)
      removeUnitOf(pos.source)
      result
    case Some(unit) =>  
    informIDE("typedTreeAt " + pos)
      parseAndEnter(unit)
    val tree = locateTree(pos)
      debugLog("at pos "+pos+" was found: "+tree.getClass+" "+tree.pos.show)
    if (stabilizedType(tree) ne null) {
      debugLog("already attributed")
      tree
    } else {
      unit.targetPos = pos
      try {
        debugLog("starting targeted type check")
        typeCheck(unit)
        println("tree not found at "+pos)
        EmptyTree
      } catch {
        case ex: TyperResult => new Locator(pos) locateIn ex.tree
      } finally {
        unit.targetPos = NoPosition
      }
    }
  }

  /** A fully attributed tree corresponding to the entire compilation unit  */
  protected def typedTree(source: SourceFile, forceReload: Boolean): Tree = {
    informIDE("typedTree" + source + " forceReload: " + forceReload)
    val unit = getOrCreateUnitOf(source)
    if (forceReload) reset(unit)
    parseAndEnter(unit)
    if (unit.status <= PartiallyChecked) {
      //newTyperRun()   // not deeded for idempotent type checker phase
      typeCheck(unit)
    }
    unit.body
  }

  /** Set sync var `response` to a fully attributed tree located at position `pos`  */
  protected def getTypedTreeAt(pos: Position, response: Response[Tree]) {
    respond(response)(typedTreeAt(pos))
  }

  /** Set sync var `response` to a fully attributed tree corresponding to the
   *  entire compilation unit  */
  protected def getTypedTree(source: SourceFile, forceReload: Boolean, response: Response[Tree]) {
    respond(response)(typedTree(source, forceReload))
  }

  /** Implements CompilerControl.askLinkPos */
  protected def getLinkPos(sym: Symbol, source: SourceFile, response: Response[Position]) {
    informIDE("getLinkPos "+sym+" "+source)
    respond(response) {
      val preExisting = unitOfFile isDefinedAt source.file
      reloadSources(List(source))
      parseAndEnter(getUnit(source).get)
      val owner = sym.owner
      if (owner.isClass) {
        val pre = adaptToNewRunMap(ThisType(owner))
        val newsym = pre.decl(sym.name) filter { alt =>
          sym.isType || {
            try {
              val tp1 = pre.memberType(alt) onTypeError NoType
              val tp2 = adaptToNewRunMap(sym.tpe)
              matchesType(tp1, tp2, false)
            } catch {
              case ex: Throwable =>
                println("error in hyperlinking: "+ex)
                ex.printStackTrace()
                false
            }
          }
        }
        if (!preExisting) removeUnitOf(source)
        if (newsym == NoSymbol) {
          debugLog("link not found "+sym+" "+source+" "+pre)
          NoPosition
        } else if (newsym.isOverloaded) {
          debugLog("link ambiguous "+sym+" "+source+" "+pre+" "+newsym.alternatives)
          NoPosition
        } else {
          debugLog("link found for "+newsym+": "+newsym.pos)
          newsym.pos
        }
      } else 
        debugLog("link not in class "+sym+" "+source+" "+owner)
        NoPosition
    }
  }

  def stabilizedType(tree: Tree): Type = tree match {
    case Ident(_) if tree.symbol.isStable => 
      singleType(NoPrefix, tree.symbol)
    case Select(qual, _) if qual.tpe != null && tree.symbol.isStable => 
      singleType(qual.tpe, tree.symbol)
    case Import(expr, selectors) =>
      tree.symbol.info match {
        case analyzer.ImportType(expr) => expr match {
          case s@Select(qual, name) => singleType(qual.tpe, s.symbol)
          case i : Ident => i.tpe
          case _ => tree.tpe
        }
        case _ => tree.tpe
      }
    
    case _ => tree.tpe
  }

  import analyzer.{SearchResult, ImplicitSearch}

  protected def getScopeCompletion(pos: Position, response: Response[List[Member]]) {
    informIDE("getScopeCompletion" + pos)
    respond(response) { scopeMembers(pos) }
  }

  private val Dollar = newTermName("$")
  
  private class Members[M <: Member] extends LinkedHashMap[Name, Set[M]] {
    override def default(key: Name) = Set()
    
    private def matching(sym: Symbol, symtpe: Type, ms: Set[M]): Option[M] = ms.find { m =>
      (m.sym.name == sym.name) && (m.sym.isType || (m.tpe matches symtpe))
    }
    
    private def keepSecond(m: M, sym: Symbol, implicitlyAdded: Boolean): Boolean = 
      m.sym.hasFlag(Flags.ACCESSOR | Flags.PARAMACCESSOR) && 
      !sym.hasFlag(Flags.ACCESSOR | Flags.PARAMACCESSOR) &&
      (!implicitlyAdded || m.implicitlyAdded)
    
    def add(sym: Symbol, pre: Type, implicitlyAdded: Boolean)(toMember: (Symbol, Type) => M) {
      if ((sym.isGetter || sym.isSetter) && sym.accessed != NoSymbol) {
        add(sym.accessed, pre, implicitlyAdded)(toMember)
      } else if (!sym.name.decode.containsName(Dollar) && !sym.isSynthetic && sym.hasRawInfo) {
        val symtpe = pre.memberType(sym) onTypeError ErrorType
        matching(sym, symtpe, this(sym.name)) match {
          case Some(m) =>
            if (keepSecond(m, sym, implicitlyAdded)) {
              //print(" -+ "+sym.name)
              this(sym.name) = this(sym.name) - m + toMember(sym, symtpe)
            }
          case None =>  
            //print(" + "+sym.name)
            this(sym.name) = this(sym.name) + toMember(sym, symtpe)
        }
      }
    }
    
    def allMembers: List[M] = values.toList.flatten
  }

  /** Return all members visible without prefix in context enclosing `pos`. */
  protected def scopeMembers(pos: Position): List[ScopeMember] = {
    typedTreeAt(pos) // to make sure context is entered
    val context = doLocateContext(pos)
    val locals = new Members[ScopeMember]
    def addScopeMember(sym: Symbol, pre: Type, viaImport: Tree) =
      locals.add(sym, pre, false) { (s, st) =>
        new ScopeMember(s, st, context.isAccessible(s, pre, false), viaImport)
      }
    //print("add scope members")
    var cx = context
    while (cx != NoContext) {
      for (sym <- cx.scope)
        addScopeMember(sym, NoPrefix, EmptyTree)
      if (cx == cx.enclClass) {
        val pre = cx.prefix
        for (sym <- pre.members) 
          addScopeMember(sym, pre, EmptyTree)
      }
      cx = cx.outer
    }

    //print("\nadd imported members")
    for (imp <- context.imports) {
      val pre = imp.qual.tpe
      for (sym <- imp.allImportedSymbols) {
        addScopeMember(sym, pre, imp.qual)
      }
    }
    // println()
    val result = locals.allMembers
//    if (debugIDE) for (m <- result) println(m)
    result
  }

  protected def getTypeCompletion(pos: Position, response: Response[List[Member]]) {
    informIDE("getTypeCompletion " + pos)
    respondGradually(response) { typeMembers(pos) }
    //if (debugIDE) typeMembers(pos)
  }

  protected def typeMembers(pos: Position): Stream[List[TypeMember]] = {
    var tree = typedTreeAt(pos)

    // if tree consists of just x. or x.fo where fo is not yet a full member name
    // ignore the selection and look in just x.
    tree match {
      case Select(qual, name) if tree.tpe == ErrorType => tree = qual
      case _ => 
    }

    val context = doLocateContext(pos)

    if (tree.tpe == null)
      // TODO: guard with try/catch to deal with ill-typed qualifiers.
      tree = analyzer.newTyper(context).typedQualifier(tree)
      
    debugLog("typeMembers at "+tree+" "+tree.tpe)

    val superAccess = tree.isInstanceOf[Super]
    val members = new Members[TypeMember]

    def addTypeMember(sym: Symbol, pre: Type, inherited: Boolean, viaView: Symbol) = {
      val implicitlyAdded = viaView != NoSymbol
      members.add(sym, pre, implicitlyAdded) { (s, st) =>
        new TypeMember(s, st, 
          context.isAccessible(s, pre, superAccess && !implicitlyAdded),
          inherited,
          viaView)
      }
    }        

    /** Create a function application of a given view function to `tree` and typechecked it.
     */
    def viewApply(view: SearchResult): Tree = {
      assert(view.tree != EmptyTree)
      analyzer.newTyper(context.makeImplicit(reportAmbiguousErrors = false))
        .typed(Apply(view.tree, List(tree)) setPos tree.pos)
        .onTypeError(EmptyTree)
    }

    val pre = stabilizedType(tree)
    val ownerTpe = tree.tpe match {
      case analyzer.ImportType(expr) => expr.tpe
      case null => pre
      case _ => tree.tpe
    }

    //print("add members")
    for (sym <- ownerTpe.members)
      addTypeMember(sym, pre, sym.owner != ownerTpe.typeSymbol, NoSymbol)
    members.allMembers #:: {
      //print("\nadd pimped")
        val applicableViews: List[SearchResult] = 
        if (ownerTpe.isErroneous) List()
        else new ImplicitSearch(
          tree, functionType(List(ownerTpe), AnyClass.tpe), isView = true, 
          context.makeImplicit(reportAmbiguousErrors = false)).allImplicits
        for (view <- applicableViews) {
          val vtree = viewApply(view)
          val vpre = stabilizedType(vtree)
          for (sym <- vtree.tpe.members) {
            addTypeMember(sym, vpre, false, view.tree.symbol)
          }
        }
      //println()
      Stream(members.allMembers)
    }
  }
  
  /** Implements CompilerControl.askLoadedTyped */
  protected def waitLoadedTyped(source: SourceFile, response: Response[Tree]) {
    getUnit(source) match {
      case Some(unit) =>
        if (unit.isUpToDate) response set unit.body
        else waitLoadedTypeResponses(source) += response
      case None =>
        reloadSources(List(source))
        waitLoadedTyped(source, response)
    }
  }
  
  /** Implements CompilerControl.askParsedEntered */
  protected def getParsedEntered(source: SourceFile, keepLoaded: Boolean, response: Response[Tree]) {
    getUnit(source) match {
      case Some(unit) =>
        getParsedEnteredNow(source, response)
      case None =>
        if (keepLoaded) {
          reloadSources(List(source))
          getParsedEnteredNow(source, response)
        } else if (outOfDate) {
          getParsedEnteredResponses(source) += response
        } else {
          getParsedEnteredNow(source, response)
        }
    }
  }
  
  /** Parses and enteres given source file, stroring parse tree in response */
  protected def getParsedEnteredNow(source: SourceFile, response: Response[Tree]) {
    respond(response) {
      onUnitOf(source) { unit =>
        parseAndEnter(unit)
        unit.body
      }
    }
  }

  // ---------------- Helper classes ---------------------------

  /** A transformer that replaces tree `from` with tree `to` in a given tree */
  class TreeReplacer(from: Tree, to: Tree) extends Transformer {
    override def transform(t: Tree): Tree = {
      if (t == from) to
      else if ((t.pos includes from.pos) || t.pos.isTransparent) super.transform(t)
      else t
    }
  }

  /** The typer run */
  class TyperRun extends Run {
    // units is always empty

    /** canRedefine is used to detect double declarations of classes and objects
     *  in multiple source files.
     *  Since the IDE rechecks units several times in the same run, these tests
     *  are disabled by always returning true here.
     */
    override def canRedefine(sym: Symbol) = true

    def typeCheck(unit: CompilationUnit): Unit = {
      applyPhase(typerPhase, unit)
    } 

    /** Apply a phase to a compilation unit
     *  @return true iff typechecked correctly
     */
    private def applyPhase(phase: Phase, unit: CompilationUnit) {
      val oldSource = reporter.getSource          
      reporter.withSource(unit.source) {
        atPhase(phase) { phase.asInstanceOf[GlobalPhase] applyPhase unit }
      }
    }
  }
  
  def newTyperRun() {
    currentTyperRun = new TyperRun
  }

  class TyperResult(val tree: Tree) extends ControlThrowable
  
  assert(globalPhase.id == 0)
  
  implicit def addOnTypeError[T](x: => T): OnTypeError[T] = new OnTypeError(x)
  
  class OnTypeError[T](op: => T) {
    def onTypeError(alt: => T) = try {
      op
    } catch {
      case ex: TypeError => 
        debugLog("type error caught: "+ex)
        alt
      //BACK 2.8 DivergentImplicit not found
//      case ex: DivergentImplicit =>
//        debugLog("divergent implicit caught: "+ex)
//        alt
    }
  }
}

object CancelException extends Exception
