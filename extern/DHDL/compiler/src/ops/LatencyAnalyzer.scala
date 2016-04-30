package dhdl.compiler.ops

import scala.reflect.{Manifest,SourceContext}
import scala.virtualization.lms.internal.Traversal
import scala.collection.mutable.HashMap

import dhdl.shared._
import dhdl.shared.ops._
import dhdl.compiler._
import dhdl.compiler.ops._

trait LatencyAnalysisExp extends LatencyModel with CounterToolsExp with PipeStageToolsExp {
  this: DHDLExp =>

  val interruptCycles = 96
  val flushCycles = 512
  val pcieCycles = 42500
  val baseCycles = flushCycles + interruptCycles + pcieCycles

  var CLK = 150.0f // Clock frequency in MHz
}


// TODO: Move elsewhere
trait QuickTraversal extends Traversal {
  import IR._

  // --- Dependencies
  val useSymsCache = true
  val symsCache = HashMap[Any, List[Sym[Any]]]()

  def symDeps(e: Any): List[Sym[Any]] = {
    if (useSymsCache && symsCache.contains(e)) symsCache(e)
    else {
      val s = syms(e)
      if (useSymsCache) symsCache(e) = s
      s
    }
  }

  // --- Scheduling
  val useScopeCache = true
  // Save inner and local scopes to avoid repeated recomputation
  val scopeCache = HashMap[Block[Any],(List[Stm], List[Stm])]()

  override def traverseBlock[A](block: Block[A]): Unit = {
    if (useScopeCache && scopeCache.contains(block)) {
      val scope = scopeCache(block)
      withInnerScope(scope._1) { traverseStmsInBlock(scope._2) }
    }
    else super.traverseBlock(block)
  }

  override def traverseBlockFocused[A](block: Block[A]): Unit = {
    focusExactScope(block) { levelScope =>
      if (useScopeCache) scopeCache(block) = (innerScope, levelScope)
      traverseStmsInBlock(levelScope)
    }
  }

  override def getStmsInBlock[A](block: Block[A]): List[Stm] = {
    if (scopeCache.contains(block)) scopeCache(block)._2
    else super.getStmsInBlock(block)
  }
}


trait ModelingTools extends QuickTraversal with PipeStageTools {
  val IR: DHDLExp with PipeStageToolsExp with LatencyModel
  import IR._

  // --- Debugging, etc.
  private var silentTraversal = false
  protected def msg(x: => Any) { if (!silentTraversal) System.out.println(x) }
  def silenceTraversal() {
    silentTraversal = true
    IR.silenceLatencyModel()
  }

  // --- State
  var inHwScope = false // In hardware scope
  var inReduce = false  // In tight reduction cycle (accumulator update)
  def latencyOf(e: Exp[Any]) = if (inHwScope) IR.latencyOf(e, inReduce) else 0L

  // TODO: Could optimize further with dynamic programming
  def latencyOfPipe(b: Block[Any]): Long = {
    val scope = getStages(b)
    var paths = HashMap[Exp[Any],Long]()
    //debug(s"Pipe latency $b:")

    def quickDFS(cur: Exp[Any]): Long = cur match {
      case Def(d) if scope.contains(cur) && !isGlobal(cur) =>
        //debug(s"Visit $cur in quickDFS (${latencyOf(cur)})")
        latencyOf(cur) + symDeps(d).map{e => paths.getOrElseUpdate(e,quickDFS(e))}.max
      case _ => 0L
    }
    if (scope.isEmpty) 0L else scope.last match {
      case e@Def(d:Reify[_]) => symDeps(d).map{e => paths.getOrElseUpdate(e,quickDFS(e))}.max
      case e => quickDFS(e)
    }
  }
  def latencyOfCycle(b: Block[Any]): Long = {
    val outerReduce = inReduce
    inReduce = true
    val out = latencyOfPipe(b)
    inReduce = outerReduce
    out
  }

  // Not a true traversal. Should it be?
  def pipeDelays(b: Block[Any], oos: Map[Exp[Any],Long] = Map.empty): List[(Exp[Any],Long)] = {
    val scope = getStages(b).filterNot(s => isGlobal(s))
    var delays = HashMap[Exp[Any],Long]() ++ scope.map{node => node -> 0L}
    var paths  = HashMap[Exp[Any],Long]() ++ oos

    def fullDFS(cur: Exp[Any]): Long = cur match {
      case Def(d) if scope.contains(cur) =>
        val deps = symDeps(d) filter (scope contains _)

        if (!deps.isEmpty) {
          val dlys = deps.map{e => paths.getOrElseUpdate(e, fullDFS(e)) }
          val critical = dlys.max

          deps.zip(dlys).foreach{ case(dep, path) =>
            if (path < critical && (critical - path) > delays(dep))
              delays(dep) = critical - path
          }
          critical + latencyOf(cur)
        }
        else latencyOf(cur)

      case s => paths.getOrElse(s, 0L) // Get preset out of scope delay
                                       // Otherwise assume 0 offset
    }
    if (!scope.isEmpty) scope.last match {
      case Def(d: Reify[_]) =>
        val deps = symDeps(d) filter (scope contains _)
        for (e <- deps) {
          paths.getOrElseUpdate(e,fullDFS(e)) // Not yet visited by any other path
        }
        // No synchronization between paths - we're done when the longest is done

      case e => fullDFS(e)
    }
    delays.toList
  }

  // Traversal
  override def traverseStm(stm: Stm) = stm match {
    case TP(s, d) => traverseNode(s, d)
  }
  def traverseNode(lhs: Exp[Any], rhs: Def[Any]): Unit

  // Reset state
  override def preprocess[A:Manifest](b: Block[A]) = {
    inHwScope = false
    inReduce = false
    super.preprocess(b)
  }
}


trait LatencyAnalyzer extends ModelingTools {
  val IR: DHDLExp with LatencyAnalysisExp
  import IR._
  import ReductionTreeAnalysis._

  //override val debugMode = true

  var cycleScope: List[Long] = Nil
  var totalCycles: Long = 0L
  var scopeLevel: Int = 0

  def latencyOfBlock(b: Block[Any]): List[Long] = {
    val outerScope = cycleScope
    cycleScope = Nil
    scopeLevel += 1
    //traverseBlock(b) -- can cause us to see things like counters as "stages"
    getControlNodes(b).foreach{
      case s@Def(d) => traverseNode(s, d)
      case _ =>
    }
    scopeLevel -= 1

    val cycles = cycleScope
    cycleScope = outerScope
    (cycles)
  }

  var savedScope: Option[List[Stm]] = None
  var savedBlock: Option[Block[Any]] = None

  def save(b: Block[Any]) {
    savedBlock = Some(b)
    savedScope = Some(innerScope)
  }

  def resume() {
    preprocess(savedBlock.get)
    innerScope = savedScope.get
    inHwScope = true
    cycleScope ::= latencyOfBlock(savedBlock.get).sum
    inHwScope = false
    postprocess(savedBlock.get)
  }

  override def run[A:Manifest](b: Block[A]): Block[A] = {
    if (savedBlock.isDefined) { resume(); b }
    else super.run(b)
  }

  def debugs(x: => Any) = debug(".."*scopeLevel + x)


  def traverseNode(lhs: Exp[Any], rhs: Def[Any]) {
    val cycles = rhs match {
      case EatReflect(Hwblock(blk)) =>
        inHwScope = true
        val body = latencyOfBlock(blk).sum
        save(blk)
        inHwScope = false
        body

      case EatReflect(Counterchain_new(ctrs,nIters)) => latencyOf(lhs)
      case EatReflect(_:TileTransfer[_]) =>
        val cycles = latencyOf(lhs)
        debugs(s"Tile Transfer $lhs ($cycles)")
        cycles

      case EatReflect(Pipe_parallel(func)) =>
        debugs(s"Parallel $lhs: ")
        val blks = latencyOfBlock(func)
        if (debugMode) blks.reverse.zipWithIndex.foreach{case (s,i) => debugs(s"- $i. $s")}
        blks.max + latencyOf(lhs)

      // --- Pipe
      case EatReflect(Unit_pipe(func)) if styleOf(lhs) == Fine =>
        debugs(s"Pipe $lhs: ")
        val pipe = latencyOfPipe(func)
        debugs(s"- pipe = $pipe")
        pipe + latencyOf(lhs)

      case EatReflect(Pipe_foreach(cchain, func, _)) if styleOf(lhs) == Fine =>
        val N = nIters(cchain)
        debugs(s"Foreach $lhs (N = $N):")
        val pipe = latencyOfPipe(func)

        debugs(s"- pipe = $pipe")
        pipe + N - 1 + latencyOf(lhs)

      case EatReflect(Pipe_reduce(cchain,_,iFunc,ld,st,func,rFunc,_,_,_,_,rV)) if styleOf(lhs) == Fine =>
        val N = nIters(cchain)
        val P = parOf(cchain).reduce(_*_)

        debugs(s"Reduce $lhs (N = $N):")

        val fuseMapReduce = canFuse(func,rFunc,rV,P)

        val body = latencyOfPipe(func)
        val internal = if (fuseMapReduce) Math.max(reductionTreeHeight(P) - 2, 0)
                       else latencyOfPipe(rFunc) * reductionTreeHeight(P)

        val cycle = latencyOfCycle(iFunc) + latencyOfCycle(ld) + latencyOfCycle(rFunc) + latencyOfCycle(st)

        debugs(s"- body  = $body")
        debugs(s"- tree  = $internal")
        debugs(s"- cycle = $cycle")
        body + internal + N*cycle + latencyOf(lhs)

      // --- Sequential
      case EatReflect(Unit_pipe(func)) if styleOf(lhs) == Disabled =>
        debugs(s"Outer Pipe $lhs:")
        val stages = latencyOfBlock(func)
        if (debugMode) stages.reverse.zipWithIndex.foreach{case (s,i) => debugs(s"- $i. $s")}

        stages.sum + latencyOf(lhs)


      // --- Metapipeline and Sequential
      case EatReflect(Pipe_foreach(cchain, func, _)) =>
        val N = nIters(cchain)
        debugs(s"Outer Foreach $lhs (N = $N):")
        val stages = latencyOfBlock(func)
        if (debugMode) stages.reverse.zipWithIndex.foreach{case (s,i) => debugs(s"- $i. $s")}

        if (styleOf(lhs) == Coarse) { stages.max * (N - 1) + stages.sum + latencyOf(lhs) }
        else                        { stages.sum * N + latencyOf(lhs) }

      case EatReflect(Pipe_reduce(cchain,_,iFunc,ld,st,func,rFunc,_,_,_,_,_)) =>
        val N = nIters(cchain)
        val P = parOf(cchain).reduce(_*_)
        debugs(s"Outer Reduce $lhs (N = $N):")

        val mapStages = latencyOfBlock(func)
        val internal = latencyOfPipe(rFunc) * reductionTreeHeight(P)
        val cycle = latencyOfCycle(iFunc) + latencyOfCycle(ld) + latencyOfCycle(rFunc) + latencyOfCycle(st)

        val reduceStage = internal + cycle
        val stages = mapStages :+ reduceStage

        if (debugMode) stages.reverse.zipWithIndex.foreach{case (s,i) => debugs(s"- $i. $s")}

        if (styleOf(lhs) == Coarse) { stages.max * (N - 1) + stages.sum + latencyOf(lhs) }
        else                        { stages.sum * N + latencyOf(lhs) }

      case EatReflect(Block_reduce(ccOuter,ccInner,_,iFunc,func,ld1,ld2,rFunc,st,_,_,_,_,_,_,_)) =>
        val Nm = nIters(ccOuter)
        val Nr = nIters(ccInner)
        val Pm = parOf(ccOuter).reduce(_*_) // Parallelization factor for map
        val Pr = parOf(ccInner).reduce(_*_) // Parallelization factor for reduce

        debugs(s"Block Reduce $lhs (Nm = $Nm, Nr = $Nr)")

        val mapStages: List[Long] = latencyOfBlock(func)
        val internal: Long = latencyOfPipe(iFunc) + latencyOfPipe(ld1) + latencyOfPipe(rFunc) * reductionTreeHeight(Pm)
        val accumulate: Long = latencyOfPipe(ld2) + latencyOfPipe(rFunc) + latencyOfPipe(st)

        val reduceStage: Long = internal + accumulate + Nr - 1
        val stages = mapStages :+ reduceStage

        if (debugMode) stages.reverse.zipWithIndex.foreach{case (s,i) => debugs(s"- $i. $s")}

        if (styleOf(lhs) == Coarse) { stages.max * (Nm - 1) + stages.sum + latencyOf(lhs) }
        else                        { stages.sum * Nm + latencyOf(lhs) }

      case _ =>
        // No general rule for combining blocks
        blocks(rhs).foreach{blk => traverseBlock(blk)}
        latencyOf(lhs)
    }
    cycleScope ::= cycles
  }

  override def preprocess[A:Manifest](b: Block[A]) = {
    cycleScope = Nil
    super.preprocess(b)
  }
  override def postprocess[A:Manifest](b: Block[A]): Block[A] = {
    // TODO: Could potentially have multiple accelerator designs in a single program
    // Eventually want to be able to support multiple accel scopes
    totalCycles = cycleScope.sum + IR.baseCycles

    msg(s"Estimated cycles: $totalCycles")
    msg(s"Estimated runtime (at " + "%.2f".format(IR.CLK) +"MHz): " + "%.8f".format(totalCycles/(IR.CLK*1000000f)) + "s")

    (b)
  }

}