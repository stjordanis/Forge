package dhdl.compiler.ops

import scala.reflect.{Manifest,SourceContext}
import scala.virtualization.lms.internal.Traversal

import dhdl.shared._
import dhdl.shared.ops._
import dhdl.compiler._
import dhdl.compiler.ops._

trait PipeLevelAnalysisExp extends PipeStageToolsExp {this: DHDLExp => }

/**
 * Analysis traversal - rectifies annotations on the "level" of each controller
 * Parallels are always ForkJoin. For all others, check if the pipe contains any other controllers
 * If it does, promote it to a CoarsePipe if it was annotated as an InnerPipe.
 * Annotate either as a Sequential or inner Pipe if no annotation previously existed
 * NOTE: AccumFold always contains inner controllers
 *
 * Sanity checks:
 * 1. Control nodes are not allowed within reduction functions
 **/
trait PipeLevelAnalyzer extends Traversal with PipeStageTools {
  val IR: DHDLExp with PipeLevelAnalysisExp
  import IR._

  override val name = "Pipe Level Analyzer"
  override val recurse = Always    // Always follow default traversal scheme
  override val eatReflect = true   // Ignore reflect wrappers
  debugMode = false

  def hasControlNodes(blocks: Block[Any]*) = blocks.map(getControlNodes(_).nonEmpty).fold(false)(_||_)

  def annotatePipeStyle(pipe: Sym[Any], blocks: Block[Any]*) = (styleOption(pipe), hasControlNodes(blocks:_*)) match {
    case (None, false) => styleOf(pipe) = InnerPipe                   // No annotations, no control nodes
    case (None, true) => styleOf(pipe) = SequentialPipe               // No annotations, control nodes
    case (Some(InnerPipe), true) => styleOf(pipe) = CoarsePipe        // Inner pipe annotation, but contains control nodes
    case _ =>                                                         // Preserve existing annotations in all other cases
  }

  def annotateAccumFold(pipe: Sym[Any]) = styleOption(pipe) match {
    case None => styleOf(pipe) = CoarsePipe                           // Metapipe by default
    case Some(InnerPipe) => styleOf(pipe) = CoarsePipe                // Metapipe if annotated as inner pipe
    case _ =>                                                         // Preserve all other existing annotations here
  }

  override def traverse(lhs: Sym[Any], rhs: Def[Any]) = rhs match {
    case _:Pipe_parallel   => styleOf(lhs) = ForkJoin
    case Hwblock(blk)      => annotatePipeStyle(lhs, blk)
    case Unit_pipe(func)   => annotatePipeStyle(lhs, func)
    case e:Pipe_foreach    => annotatePipeStyle(lhs, e.func)
    case e:Pipe_fold[_,_]  =>
      annotatePipeStyle(lhs, e.func)
      if (hasControlNodes(e.rFunc)) stageError("Reduction controller defined here defines control nodes within the reduction function. This is currently disallowed")(e.ctx)

    case e:Accum_fold[_,_] =>
      annotateAccumFold(lhs)
      if (hasControlNodes(e.rFunc)) stageError("Reduction controller defined here defines control nodes within the reduction function. This is currently disallowed")(e.ctx)

    case _ => super.traverse(lhs, rhs)
  }
}