package dhdl.compiler.ops

import scala.virtualization.lms.common.{ScalaGenEffect, DotGenEffect, MaxJGenEffect}
import scala.reflect.{Manifest,SourceContext}
import ppl.delite.framework.transform.{DeliteTransform}

import dhdl.compiler._
import dhdl.compiler.ops._

trait LoweredPipeOpsExp extends ExternPrimitiveTypesExp with MemoryTemplateOpsExp {
  this: DHDLExp =>

  // --- Nodes
  case class ParPipeForeach(
    cc:   Exp[CounterChain],
    func: Block[Unit],
    inds: List[List[Sym[FixPt[Signed,B32,B0]]]]
  )(implicit val ctx: SourceContext) extends Def[Pipeline]

  case class ParPipeReduce[T,C[T]](
    cc:    Exp[CounterChain],
    accum: Exp[C[T]],
    func:  Block[Unit],
    rFunc: Block[T],
    inds:  List[List[Sym[FixPt[Signed,B32,B0]]]],
    acc:   Sym[C[T]],
    rV:    (Sym[T], Sym[T])
  )(implicit val ctx: SourceContext, val mT: Manifest[T], val mC: Manifest[C[T]]) extends Def[Pipeline]

  // --- Internal API

  // --- Mirroring
  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit pos: SourceContext): Exp[A] = e match {
    case e@ParPipeForeach(cc,func,i) => reflectPure(ParPipeForeach(f(cc),f(func),i)(e.ctx))(mtype(manifest[A]),pos)
    case Reflect(e@ParPipeForeach(cc,func,i), u, es) => reflectMirrored(Reflect(ParPipeForeach(f(cc),f(func),i)(e.ctx), mapOver(f,u), f(es)))(mtype(manifest[A]),pos)

    case e@ParPipeReduce(cc,a,b,r,i,acc,rV) => reflectPure(ParPipeReduce(f(cc),f(a),f(b),f(r),i,acc,rV)(e.ctx,e.mT,e.mC))(mtype(manifest[A]),pos)
    case Reflect(e@ParPipeReduce(cc,a,b,r,i,acc,rV), u, es) => reflectMirrored(Reflect(ParPipeReduce(f(cc),f(a),f(b),f(r),i,acc,rV)(e.ctx,e.mT,e.mC), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)

    case _ => super.mirror(e,f)
  }

  // --- Dependencies
  override def syms(e: Any): List[Sym[Any]] = e match {
    case ParPipeForeach(cc,func,inds) => syms(cc) ::: syms(func)
    case ParPipeReduce(cc,accum,func,rFunc,inds,acc,rV) => syms(cc) ::: syms(accum) ::: syms(func) ::: syms(rFunc)
    case _ => super.syms(e)
  }
  override def readSyms(e: Any): List[Sym[Any]] = e match {
    case ParPipeForeach(cc,func,inds) => readSyms(cc) ::: readSyms(func)
    case ParPipeReduce(cc,accum,func,rFunc,inds,acc,rV) => readSyms(cc) ::: readSyms(accum) ::: readSyms(func) ::: readSyms(rFunc)
    case _ => super.readSyms(e)
  }
  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case ParPipeForeach(cc,func,inds) => freqNormal(cc) ::: freqNormal(func)
    case ParPipeReduce(cc,accum,func,rFunc,inds,acc,rV) => freqNormal(cc) ::: freqNormal(accum) ::: freqNormal(func) ::: freqNormal(rFunc)
    case _ => super.symsFreq(e)
  }
  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case ParPipeForeach(cc,func,inds) => inds.flatten ::: effectSyms(func)
    case ParPipeReduce(cc,accum,func,rFunc,inds,acc,rV) => inds.flatten ::: effectSyms(func) ::: effectSyms(rFunc) ::: List(acc, rV._1, rV._2)
    case _ => super.boundSyms(e)
  }
}

trait ScalaGenLoweredPipeOps extends ScalaGenEffect {
  val IR: LoweredPipeOpsExp with DHDLCodegenOps
  import IR._

  def emitParallelizedLoop(iters: List[List[Sym[FixPt[Signed,B32,B0]]]], cchain: Exp[CounterChain])(emitBlk: => Unit) = {
    iters.zipWithIndex.foreach{ case (is, i) =>
      stream.println("for( " + quote(cchain) + "_vec" + i + " <- " + quote(cchain) + ".apply(" + i + ".toInt)) {")
      is.zipWithIndex.foreach{ case (iter, j) =>
        stream.println("  val "+quote(iter)+" = " + quote(cchain) + "_vec" + i + ".apply(" + j + ".toInt)")
      }
    }
    emitBlk
    stream.println("}" * iters.length)
  }

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case e@ParPipeForeach(cchain, func, inds) =>
      emitParallelizedLoop(inds, cchain){ emitBlock(func) }
      emitValDef(sym, "()")

    case e@ParPipeReduce(cchain, accum, func, rFunc, inds, acc, rV) =>
      emitValDef(acc, quote(accum))
      emitParallelizedLoop(inds, cchain){ emitBlock(func) }
      emitValDef(sym, "()")

    case _ => super.emitNode(sym, rhs)
  }
}

trait MaxJGenLoweredPipeOps extends MaxJGenControllerTemplateOps {
  val IR: LoweredPipeOpsExp with ControllerTemplateOpsExp with TpesOpsExp with ParallelOpsExp
          with PipeOpsExp with OffChipMemOpsExp with RegOpsExp with ExternCounterOpsExp
          with DHDLCodegenOps with NosynthOpsExp with DeliteTransform
  import IR._

  def emitParallelizedLoop(iters: List[List[Sym[FixPt[Signed,B32,B0]]]], cchain: Exp[CounterChain]) = {
    val Def(EatReflect(Counterchain_new(counters, nIter))) = cchain

    iters.zipWithIndex.foreach{ case (is, i) =>
      if (is.size == 1) { // This level is not parallelized, so assign the iter as-is
          emit("DFEVar " + quote(is(0)) + " = " + quote(counters(i)) + ";");
      } else { // This level IS parallelized, index into the counters correctly
        is.zipWithIndex.foreach{ case (iter, j) =>
          emit("DFEVar " + quote(iter) + " = " + quote(counters(i)) + "[" + j + "];")
        }
      }
    }
  }

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case e@ParPipeForeach(cchain, func, inds) =>
      controlNodeStack.push(sym)
      emitComment(s"""ParPipeForeach ${quote(sym)} = ParPipeForeach(${quote(cchain)}) {""")
      styleOf(sym) match {
        case StreamPipe => emitComment(s"""StrmPipe to be emitted""")
        case CoarsePipe => emitComment(s"""MPSM to be emitted""")
        case InnerPipe => emitComment(s"""PipeSM to be emitted""")
        case SequentialPipe => emitComment(s"""SeqSM to be emitted""")
        case _ => emitComment(s"""ParPipeForeach style: ${styleOf(sym)}""")
      }
      emitController(sym, Some(cchain))
      emitParallelizedLoop(inds, cchain)
      emitBlock(func)
      emitComment(s"""} ParPipeForeach ${quote(sym)}""")
      controlNodeStack.pop

    case e@ParPipeReduce(cchain, accum, func, rFunc, inds, acc, rV) =>
      controlNodeStack.push(sym)
      emitComment(s"""ParPipeReduce ${quote(sym)} = ParPipeReduce(${quote(cchain)}, ${quote(accum)}) {""")
      styleOf(sym) match {
        case CoarsePipe => emitComment(s"""MPSM to be emitted""")
        case InnerPipe => emitComment(s"""PipeSM to be emitted""")
        case SequentialPipe => emitComment(s"""SeqSM to be emitted""")
        case _ => emitComment(s"""ParPipeForeach style: ${styleOf(sym)}""")
      }

      val ConstFix(rstVal) = resetValue(acc.asInstanceOf[Sym[Reg[Any]]])
			val ts = tpstr(parOf(acc))(acc.tp.typeArguments.head, implicitly[SourceContext])
      emit(s"""DelayLib ${quote(acc)}_lib = new DelayLib(this, $ts, new Bits($ts.getTotalBits(), $rstVal));""")
      if (parOf(acc) > 1) {
        emit(s"""${quote(maxJPre(acc))} ${quote(acc)} = ${quote(acc)}_lib.readv();""")
      } else {
        emit(s"""${quote(maxJPre(acc))} ${quote(acc)} = ${quote(acc)}_lib.read();""")
      }
      emit(s"""DFEVar ${quote(acc)}_delayed = ${quote(acc)}.getType().newInstance(this);""")
      emitController(sym, Some(cchain))
      emitParallelizedLoop(inds, cchain)
      emitBlock(func)
      emit(s"""${quote(accum)}_lib.write(${quote(acc)}, constant.var(true), constant.var(false));""")

      emitComment(s"""} ParPipeReduce ${quote(sym)}""")
      controlNodeStack.pop

    case _ => super.emitNode(sym, rhs)
  }
}
