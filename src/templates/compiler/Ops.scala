package ppl.dsl.forge
package templates
package compiler

import java.io.{BufferedWriter, FileWriter, PrintWriter}
import scala.tools.nsc.io._
import scala.collection.mutable.ArrayBuffer
import scala.virtualization.lms.common._

import core._
import shared.BaseGenOps
import Utilities._

trait DeliteGenOps extends BaseGenOps {
  this: ForgeCodeGenDelite =>

  val IR: ForgeApplicationRunner with ForgeExp with ForgeOpsExp
  import IR._

  var activeGenerator: CodeGenerator = _

  def baseOpsCls(opsGrp: DSLOps) = {
    if (opsGrp.ops.exists(_.style == compilerMethod)) opsGrp.grp.name + "CompilerOps"
    else opsGrp.name
  }
  def baseExpCls(opsGrp: DSLOps) = {
    // in order of decreasing inclusiveness
    if (opsGrp.ops.exists(o => grpIsTpe(o.grp) && ForgeCollections.contains(grpAsTpe(o.grp)) && DataStructs.contains(grpAsTpe(o.grp)))) "DeliteCollectionOpsExp with DeliteStructsExp"
    else if (opsGrp.ops.exists(o => grpIsTpe(o.grp) && ForgeCollections.contains(grpAsTpe(o.grp)))) "DeliteCollectionOpsExp"
    else if (opsGrp.ops.exists(o => grpIsTpe(o.grp) && DataStructs.contains(grpAsTpe(o.grp)))) "BaseFatExp with DeliteStructsExp"
    else "BaseFatExp with EffectExp" // we use codegen *GenFat, which requires EffectExp
  }

  //TODO: better way to check if the string contains block
  private def containsBlock(b: String): Boolean = {
    if(b.contains("emitBlock")) true
    else false
  }

  // bound symbol for the captured variable of a block
  private var boundArg: String = _

  override def quote(x: Exp[Any]): String = x match {
    case Def(QuoteBlockResult(func,args,ret,captured)) =>
      // bind function args to captured args
      var boundStr: String = ""
      var i = 0
      if (!isThunk(func.tpe)) {
        for (a <- args) {
          // have to be careful about automatic string lifting here
          val boundArg_saved = boundArg
          boundArg = replaceWildcards(boundArgName(func,a))
          if (containsBlock(replaceWildcards(captured(i)))) {
            // when the captured variable is another block,
            // declare the varible, emit block, and assign the result to the variable at the end of the block.
            val add = nl + "emitVarDecl(" + replaceWildcards(boundArgName(func,a))+ ".asInstanceOf[Sym[Any]])" + nl + replaceWildcards(captured(i))
            boundStr += add
          }
          else {
            val add: String = (nl + "emitValDef(" + replaceWildcards(boundArgName(func,a)) + ".asInstanceOf[Sym[Any]],\"" + replaceWildcards(captured(i)) + "\")")
            boundStr += add
          }
          boundArg = boundArg_saved
          i += 1
        }
      }

      if (activeGenerator == cpp && boundArg == null && ret != MUnit)
        warn("Block " + func.name + " returns non-unit type result. C++ target may not work properly." + boundStr)

      // the new-line formatting is admittedly weird; we are using a mixed combination of actual new-lines (for string splitting at Forge)
      // and escaped new-lines (for string splitting at Delite), based on how we received strings from string interpolation.
      // FIX: using inconsistent newline character, not platform independent
      val out = "{ \"" + boundStr +
        nl + "emitBlock(" + func.name + ")" +
        (if (ret != MUnit && boundArg == null)
           (nl + "quote(getBlockResult(" + func.name + "))+\"\\n\"")
         else if (ret != MUnit && boundArg != null)
           (nl + "emitAssignment(" + boundArg + ".asInstanceOf[Sym[Any]], quote(getBlockResult(" + func.name + ")))")
         else ""
        ) +
        nl + " \" }\\n "
       if(ret != MUnit && boundArg != null) "\"" + out + "\"" else out

    case Def(QuoteSeq(argName)) => "Seq("+unquotes(argName+".map(quote).mkString("+quotes(",")+")")+")"

    case Const(s: String) if quoteLiterally => s  // no quotes, wildcards will be replaced later in inline
    case Const(s: String) => replaceWildcards(super.quote(s))  // quote first, then insert wildcards

    case _ => super.quote(x)
  }

  // IR node names
  def makeOpNodeName(o: Rep[DSLOp], suffix: String = "") = {
    Labels.get(o).map(_.capitalize).getOrElse {
      val i = nameClashId(o, clasher = nameClashesSimple)
      o.style match {
        case `staticMethod` => o.grp.name + i + "Object_" + sanitize(o.name).capitalize + suffix
        case `compilerMethod` => o.name.capitalize + suffix
        case _ => o.grp.name + i + "_" + sanitize(o.name).capitalize + suffix
      }
    }
  }

  // non-thunk functions use bound sym inputs, so we need to add the bound syms to the args
  // we need both the func name and the func arg name to completely disambiguate the bound symbol, but the unique name gets quite long..
  // could use a local numbering scheme inside each op. should also consider storing the bound syms inside the case class body, instead of the parameter list, which could simplify things.
  def boundArgName(func: Rep[DSLArg], arg: Rep[DSLArg]) = "f_" + func.name  + "_" + simpleArgName(arg)
  def boundArgAnonName(func: Rep[DSLArg], arg: Rep[DSLArg], i: Int) = "f_" + opArgPrefix + i + "_" + simpleArgName(arg)
  def makeArgsWithBoundSyms(args: List[Rep[DSLArg]], opType: OpType) =
    makeArgs(args, t => t match {
      case Def(Arg(name, f@Def(FTpe(fargs,ret,freq)), d2)) if opType.isInstanceOf[CodeGen] && !isThunk(f) => (simpleArgName(t) :: fargs.map(a => boundArgName(t,a))).mkString(",")
      case _ => simpleArgName(t)
  })
  def makeArgsWithBoundSymsWithType(args: List[Rep[DSLArg]], opType: OpType, typify: Rep[DSLType] => String = repify) =
    makeArgs(args, t => t match {
      case Def(Arg(name, f@Def(FTpe(fargs,ret,freq)), d2)) if opType.isInstanceOf[CodeGen] && !isThunk(f) => ((simpleArgName(t) + ": " + typify(t.tpe)) :: fargs.map(a => boundArgName(t,a) + ": " + typify(a.tpe))).mkString(",")
      case _ => argify(t, typify)
  })

  def makeOpSimpleNodeNameWithArgs(o: Rep[DSLOp]) = makeOpNodeName(o) + makeArgsWithBoundSyms(o.args, Impls(o))
  def makeOpSimpleNodeNameWithAnonArgs(o: Rep[DSLOp], suffix: String = "") = {
    // scalac typer error unless we break up the expression
    val z = o.args.zipWithIndex.map{ case (a,i) => arg(opArgPrefix + i, a.tpe, a.default) }
    makeOpNodeName(o) + suffix + makeArgsWithBoundSyms(z, Impls(o))
    // makeOpNodeName(o) + suffix + makeArgsWithBoundSyms(o.args.zipWithIndex.map{ case (a,i) => arg(opArgPrefix + i, a.tpe, a.default) })
  }

  // TODO: is this code obsoleted by quote(tpeInst(hkTpe,tpeArg))?? TEST AND REMOVE
  // TODO: tpeArg should be a List that is the same length as the tpePars in hkTpe
  def makeTpeInst(hkTpe: Rep[DSLType], tpeArg: Rep[DSLType]) = hkTpe match {
    case Def(Tpe(s,Nil,stage)) => s // rather lenient, might get strange results in an improperly specified dsl
    case Def(Tpe(s,List(z),stage)) => s + "[" + quote(tpeArg) + "]"
    case Def(Tpe(s,args,stage)) => err("tried to instantiate tpe " + hkTpe.name + " with arg " + tpeArg.name + ", but " + hkTpe.name + " requires " + args.length + " type parameters")
  }

  def makeTpeClsPar(b: TypeClassSignature, t: Rep[DSLType]) = {
    val body = opIdentifierPrefix + "." + b.prefix + quote(t)
    b.wrapper match {
      case Some(w) => w + "(" + body + ")"
      case None => body
    }
  }

  def makeFuncSignature(argTpePars: List[Rep[DSLType]], retTpePar: Rep[DSLType]) = {
    val argStr = if (argTpePars == Nil) "" else argTpePars.map(t => "Rep["+quote(t)+"]").mkString("(",",",")") + " => "
    Some(argStr + "Rep[" + quote(retTpePar) + "]")
  }

  def emitImplMethod(o: Rep[DSLOp], func: Rep[String], postfix: String, returnTpe: Option[String], stream: PrintWriter, indent: Int = 2) {
    emitWithIndent(makeOpImplMethodSignature(o, postfix, returnTpe) + " = {", stream, indent)
    inline(o, func, quoteLiteral).split(nl).foreach { line => emitWithIndent(line, stream, indent+2 )}
    emitWithIndent("}", stream, indent)
    stream.println()
  }

  def emitImpls(opsGrp: DSLOps, stream: PrintWriter) {
    emitBlockComment("Op Impls", stream)
    stream.println()
    stream.println("trait " + opsGrp.name + "Impl {")
    stream.println("  this: " + dsl + "Compiler with " + dsl + "Lift => ")
    stream.println()
    for (o <- unique(opsGrp.ops)) {
      Impls(o) match {
        case single:SingleTask =>
          emitImplMethod(o, single.func, "", None, stream)
        case composite:Composite =>
          emitImplMethod(o, composite.func, "", None, stream)
        case map:Map =>
          emitImplMethod(o, map.func, "_map", makeFuncSignature(map.tpePars._1, map.tpePars._2), stream)
        case zip:Zip =>
          emitImplMethod(o, zip.func, "_zip", makeFuncSignature((zip.tpePars._1, zip.tpePars._2), zip.tpePars._3), stream)
        case reduce:Reduce =>
          emitImplMethod(o, reduce.func, "_reduce", makeFuncSignature((reduce.tpePar, reduce.tpePar), reduce.tpePar), stream)
          emitImplMethod(o, reduce.zero, "_zero", makeFuncSignature(Nil, reduce.tpePar), stream)
        case mapreduce:MapReduce =>
          emitImplMethod(o, mapreduce.map, "_map",  makeFuncSignature(mapreduce.tpePars._1, mapreduce.tpePars._2), stream)
          emitImplMethod(o, mapreduce.reduce, "_reduce", makeFuncSignature((mapreduce.tpePars._2, mapreduce.tpePars._2), mapreduce.tpePars._2), stream)
          emitImplMethod(o, mapreduce.zero, "_zero", makeFuncSignature(Nil, mapreduce.tpePars._2), stream)
          if (mapreduce.cond.isDefined) {
            emitImplMethod(o, mapreduce.cond.get, "_cond", makeFuncSignature(mapreduce.tpePars._1, MBoolean), stream)
          }
        case filter:Filter =>
          emitImplMethod(o, filter.cond, "_cond", makeFuncSignature(filter.tpePars._1, MBoolean), stream)
          emitImplMethod(o, filter.func, "_map", makeFuncSignature(filter.tpePars._1, filter.tpePars._2), stream)
        case flatmap:FlatMap =>
          val outCol = getHkTpe(o.retTpe)
          emitImplMethod(o, flatmap.func, "_func", makeFuncSignature(flatmap.tpePars._1, tpeInst(outCol, flatmap.tpePars._2)), stream)
        case gb:GroupBy =>
          if (gb.cond.isDefined) {
            emitImplMethod(o, gb.cond.get, "_cond", makeFuncSignature(gb.tpePars._1, MBoolean), stream)
          }
          emitImplMethod(o, gb.key, "_key", makeFuncSignature(gb.tpePars._1, gb.tpePars._2), stream)
          emitImplMethod(o, gb.map, "_map",  makeFuncSignature(gb.tpePars._1, gb.tpePars._3), stream)
        case gbr:GroupByReduce =>
          if (gbr.cond.isDefined) {
            emitImplMethod(o, gbr.cond.get, "_cond", makeFuncSignature(gbr.tpePars._1, MBoolean), stream)
          }
          emitImplMethod(o, gbr.key, "_key", makeFuncSignature(gbr.tpePars._1, gbr.tpePars._2), stream)
          emitImplMethod(o, gbr.map, "_map",  makeFuncSignature(gbr.tpePars._1, gbr.tpePars._3), stream)
          emitImplMethod(o, gbr.zero, "_zero",  makeFuncSignature(Nil, gbr.tpePars._3), stream)
          emitImplMethod(o, gbr.reduce, "_reduce", makeFuncSignature((gbr.tpePars._3, gbr.tpePars._3), gbr.tpePars._3), stream)
        case foreach:Foreach =>
          emitImplMethod(o, foreach.func, "_func", makeFuncSignature(foreach.tpePar, MUnit), stream)
        case _ =>
      }
    }
    stream.println("}")
  }

  def emitOpExp(opsGrp: DSLOps, stream: PrintWriter) {
    emitBlockComment("IR Definitions", stream)
    stream.println()

    stream.println("trait " + opsGrp.name + "Exp extends " + baseOpsCls(opsGrp) + " with " + baseExpCls(opsGrp) + " {")
    stream.println("  this: " + dsl + "Exp => ")
    stream.println()

    val uniqueOps = unique(opsGrp.ops)

    emitIRNodes(uniqueOps, stream)
    stream.println()
    emitNodeConstructors(uniqueOps, stream)
    stream.println()
    emitSyms(uniqueOps, stream)
    stream.println()
    emitAliasInfo(uniqueOps, stream)
    stream.println()
    emitMirrors(uniqueOps, stream)
    stream.println()
    emitDeliteCollection(opsGrp, stream)
    stream.println()
    emitStructMethods(opsGrp, stream)
    stream.println("}")
  }

  def hasIRNode(o: Rep[DSLOp]) = Impls(o) match {
    case _:Composite | _:Redirect | _:Getter | _:Setter => false
    case _ => true
  }

  def hasMultipleIRNodes(o: Rep[DSLOp]) = Impls(o) match {
    case _:GroupBy | _:GroupByReduce => true
    case _ => false
  }

  /**
   * Op helpers we use to compose IR nodes
   */
  def emitOpNodeHeader(o: Rep[DSLOp], opStr: String, stream: PrintWriter) {
      stream.println(" extends " + opStr + " {")
      for (targ <- o.tpePars) {
        for (b <- targ.ctxBounds) {
          stream.println("    val " + b.prefix + targ.name + " = implicitly[" + b.name + "[" + targ.name + "]]")
        }
      }
    }

  def emitOpNodeFooter(o: Rep[DSLOp], stream: PrintWriter) {
    stream.println("  }")
  }

  def emitGroupByCommonVals(o: Rep[DSLOp], in: Rep[DSLArg], cond: Option[Rep[String]], tpePars: (Rep[DSLType],Rep[DSLType],Rep[DSLType]), stream: PrintWriter) {
    val inDc = ForgeCollections(getHkTpe(in.tpe))
    stream.println()
    stream.println("    val in = " + in.name)
    if (cond.isDefined) {
      stream.println("    def cond = " + makeOpImplMethodNameWithArgs(o, "_cond"))
    }
    else {
      stream.println("    def cond: Exp["+quote(tpePars._1)+"] => Exp[Boolean] = null")
    }
    stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(in))")
    stream.println("    def keyFunc = " + makeOpImplMethodNameWithArgs(o, "_key"))
  }

  def emitGroupByCommonNodes(o: Rep[DSLOp], in: Rep[DSLArg], cond: Option[Rep[String]], tpePars: (Rep[DSLType],Rep[DSLType],Rep[DSLType]), stream: PrintWriter) {
    val inDc = ForgeCollections(getHkTpe(in.tpe))
    val outerColTpe = MArray
    val outDc = ForgeCollections(outerColTpe)

    // keys
    stream.print("  case class " + makeOpNodeName(o, "Keys") + makeTpeParsWithBounds(o.tpePars))
    stream.print(makeOpArgsWithType(o))
    stream.print(makeOpImplicitArgsWithType(o,true))
    emitOpNodeHeader(o, "DeliteOpFilteredGroupByReduce[" + quote(tpePars._1) + "," + quote(tpePars._2) + "," + quote(tpePars._2) + "," + quote(tpeInst(outerColTpe, tpePars._2)) + "]", stream)
    emitGroupByCommonVals(o, in, cond, tpePars, stream)
    stream.println("    def valFunc = keyFunc")
    stream.println("    def reduceFunc = (a,b) => a")
    stream.println("    def zero = unit(null).asInstanceOf["+repify(tpePars._2)+"]")

    // see comment about innerDcArg in library/Ops.scala
    val keysDcArg = if (quote(in.tpe) == quote(outerColTpe)) "in" else "null.asInstanceOf["+repify(tpeInst(outerColTpe, tpePars._2))+"]"
    stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,tpePars._2)) + "("+keysDcArg+", len)")

    emitOpNodeFooter(o, stream)
    stream.println()

    // index
    stream.print("  case class " + makeOpNodeName(o, "Index") + makeTpeParsWithBounds(o.tpePars))
    stream.print(makeOpArgsWithType(o))
    stream.print(makeOpImplicitArgsWithType(o,true))
    emitOpNodeHeader(o, "DeliteOpBuildIndex[" + quote(tpePars._1) + "," + quote(tpePars._2) + ",DeliteIndex[" + quote(tpePars._2) + "]]", stream)
    emitGroupByCommonVals(o, in, cond, tpePars, stream)
    emitOpNodeFooter(o, stream)
    stream.println()
  }


  /**
   * IR node implementation for each op type
   */
  def emitIRNodes(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    def matchChunkInput(x: Any): Int = x match {
      case i:Int => i.asInstanceOf[Int]
      case s:String => -1
      case _ => 0
    }
    // IR nodes
    for (o <- uniqueOps if hasIRNode(o) && !hasMultipleIRNodes(o)) {
      stream.print("  case class " + makeOpNodeName(o) + makeTpeParsWithBounds(o.tpePars))
      if (Impls(o).isInstanceOf[CodeGen]) stream.print(makeArgsWithBoundSymsWithType(o.args, Impls(o), blockify))
      else stream.print(makeOpArgsWithType(o))
      stream.print(makeOpImplicitArgsWithType(o,true))

      Impls(o) match {
        case codegen:CodeGen =>
          emitOpNodeHeader(o, "Def[" + quote(o.retTpe) + "]", stream)
        case single:SingleTask =>
          emitOpNodeHeader(o, "DeliteOpSingleTask[" + quote(o.retTpe) + "](reifyEffectsHere("+makeOpImplMethodNameWithArgs(o)+"))", stream)
        case Allocates(tpe,init) =>
          emitOpNodeHeader(o, "DeliteStruct[" + quote(o.retTpe) + "]", stream)
          val data = DataStructs(tpe)
          val elemsPure = data.fields.zip(init) map { case ((name,t),i) => ("\""+name+"\"", inline(o,i,quoteLiteral)) }
          val elems = if (o.effect == mutable) elemsPure map { case (k,v) => (k, "var_new("+v+").e") } else elemsPure
          stream.println("    val elems = copyTransformedElems(collection.Seq(" + elems.mkString(",") + "))")
        case map:Map =>
          val colTpe = getHkTpe(o.retTpe)
          val outDc = ForgeCollections(colTpe)
          val in = o.args.apply(map.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          emitOpNodeHeader(o, "DeliteOpMap[" + quote(map.tpePars._1) + "," + quote(map.tpePars._2) + "," + makeTpeInst(colTpe, map.tpePars._2) + "]", stream)
          stream.println()
          stream.println("    val in = " + in.name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_map"))
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,map.tpePars._2)) + "(in, len)")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(map.numDynamicChunks))
        case zip:Zip =>
          val colTpe = getHkTpe(o.retTpe)
          val outDc = ForgeCollections(colTpe)
          val inA = o.args.apply(zip.argIndices._1)
          val inDc = ForgeCollections(getHkTpe(inA.tpe))
          emitOpNodeHeader(o, "DeliteOpZipWith[" + quote(zip.tpePars._1) + "," + quote(zip.tpePars._2) + "," + quote(zip.tpePars._3) + "," + makeTpeInst(colTpe,zip.tpePars._3) + "]", stream)
          stream.println()
          stream.println("    val inA = " + inA.name)
          stream.println("    val inB = " + o.args.apply(zip.argIndices._2).name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_zip"))
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,inA.tpe,zip.tpePars._3)) + "(inA, len)")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(inA))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(zip.numDynamicChunks))
        case reduce:Reduce =>
          val col = o.args.apply(reduce.argIndex)
          val dc = ForgeCollections(getHkTpe(col.tpe))
          emitOpNodeHeader(o, "DeliteOpReduce[" + quote(reduce.tpePar) + "]", stream)
          stream.println()
          stream.println("    val in = " + col.name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_reduce"))
          stream.println("    def zero = " + makeOpImplMethodNameWithArgs(o, "_zero"))
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(dc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(reduce.numDynamicChunks))
        case mapreduce:MapReduce =>
          val col = o.args.apply(mapreduce.argIndex)
          val dc = ForgeCollections(getHkTpe(col.tpe))
          if (mapreduce.cond.isDefined) {
            emitOpNodeHeader(o, "DeliteOpFilterReduce[" + quote(mapreduce.tpePars._1) + "," + quote(mapreduce.tpePars._2) + "]", stream)
          }
          else {
            emitOpNodeHeader(o, "DeliteOpMapReduce[" + quote(mapreduce.tpePars._1) + "," + quote(mapreduce.tpePars._2) + "]", stream)
          }
          stream.println()
          stream.println("    val in = " + col.name)
          stream.println("    def zero = " + makeOpImplMethodNameWithArgs(o, "_zero"))
          stream.println("    def reduce = " + makeOpImplMethodNameWithArgs(o, "_reduce"))
          // kind of silly, but DeliteOpFilterReduce and DeliteOpMapReduce have different names for the mapping function
          if (mapreduce.cond.isDefined) {
            stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_map"))
            stream.println("    def cond = " + makeOpImplMethodNameWithArgs(o, "_cond"))
          }
          else {
            stream.println("    def map = " + makeOpImplMethodNameWithArgs(o, "_map"))
          }
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(dc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(mapreduce.numDynamicChunks))
        case filter:Filter =>
          val colTpe = getHkTpe(o.retTpe)
          val outDc = ForgeCollections(colTpe)
          val in = o.args.apply(filter.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          emitOpNodeHeader(o, "DeliteOpFilter[" + quote(filter.tpePars._1) + "," + quote(filter.tpePars._2) + "," + makeTpeInst(colTpe,filter.tpePars._2) + "]", stream)
          stream.println()
          stream.println("    val in = " + in.name)
          stream.println("    def cond = " + makeOpImplMethodNameWithArgs(o, "_cond"))
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_map"))
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,filter.tpePars._2)) + "(in, len)")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(filter.numDynamicChunks))
        case flatmap:FlatMap =>
          val colTpe = getHkTpe(o.retTpe)
          val outDc = ForgeCollections(colTpe)
          val in = o.args.apply(flatmap.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          emitOpNodeHeader(o, "DeliteOpFlatMap[" + quote(flatmap.tpePars._1) + "," + quote(flatmap.tpePars._2) + "," + makeTpeInst(colTpe,flatmap.tpePars._2) + "]", stream)
          stream.println()
          stream.println("    val in = " + in.name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_func"))
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,flatmap.tpePars._2)) + "(in, len)")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(flatmap.numDynamicChunks))
        case foreach:Foreach =>
          val col = o.args.apply(foreach.argIndex)
          val dc = ForgeCollections(getHkTpe(col.tpe))
          emitOpNodeHeader(o, "DeliteOpForeach[" + quote(foreach.tpePar) + "]", stream)
          stream.println()
          stream.println("    val in = " + col.name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_func"))
          stream.println("    def sync = n => unit(List())")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(dc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(foreach.numDynamicChunks))
      }
      emitOpNodeFooter(o, stream)
      stream.println()
    }

    for (o <- uniqueOps if hasMultipleIRNodes(o)) {
      Impls(o) match {
        case gb:GroupBy =>
          val in = o.args.apply(gb.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          // val outerColTpe = getHkTpe(o.retTpe)
          val outerColTpe = MArray
          val outDc = ForgeCollections(outerColTpe)
          val innerColTpe = getHkTpe(gb.tpePars._4)
          val innerDc = ForgeCollections(innerColTpe)

          emitGroupByCommonNodes(o, in, gb.cond, (gb.tpePars._1,gb.tpePars._2,gb.tpePars._3), stream)

          // values
          stream.print("  case class " + makeOpNodeName(o) + makeTpeParsWithBounds(o.tpePars))
          stream.print(makeOpArgsWithType(o))
          stream.print(makeOpImplicitArgsWithType(o,true))
          emitOpNodeHeader(o, "DeliteOpFilteredGroupBy[" + quote(gb.tpePars._1) + "," + quote(gb.tpePars._2) + "," + quote(gb.tpePars._3) + "," + quote(tpeInst(innerColTpe, gb.tpePars._3)) + "," + makeTpeInst(outerColTpe,tpeInst(innerColTpe, gb.tpePars._3)) + "]", stream)
          emitGroupByCommonVals(o, in, gb.cond, (gb.tpePars._1,gb.tpePars._2,gb.tpePars._3), stream)
          stream.println("    def valFunc = " + makeOpImplMethodNameWithArgs(o, "_map"))

          val innerDcArg = if (quote(in.tpe) == quote(innerColTpe)) "in" else "null.asInstanceOf["+repify(tpeInst(innerColTpe, gb.tpePars._3))+"]"
          stream.println("    override def allocI(len: Exp[Int]) = " + makeOpMethodName(innerDc.alloc) + makeTpePars(instAllocReturnTpe(innerDc.alloc, in.tpe, gb.tpePars._3)) + "("+innerDcArg+", len)")

          val outDcArg = if (quote(in.tpe) == quote(outerColTpe)) "in" else "null.asInstanceOf["+repify(tpeInst(outerColTpe, tpeInst(innerColTpe, gb.tpePars._3)))+"]"
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc, in.tpe, tpeInst(innerColTpe, gb.tpePars._3))) + "("+outDcArg+", len)")

          emitOpNodeFooter(o, stream)
          stream.println()

        case gbr:GroupByReduce =>
          val in = o.args.apply(gbr.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          // val outerColTpe = getHkTpe(o.retTpe)
          val outerColTpe = MArray
          val outDc = ForgeCollections(outerColTpe)

          emitGroupByCommonNodes(o, in, gbr.cond, gbr.tpePars, stream)

          stream.print("  case class " + makeOpNodeName(o) + makeTpeParsWithBounds(o.tpePars))
          stream.print(makeOpArgsWithType(o))
          stream.print(makeOpImplicitArgsWithType(o,true))
          emitOpNodeHeader(o, "DeliteOpFilteredGroupByReduce[" + quote(gbr.tpePars._1) + "," + quote(gbr.tpePars._2) + "," + quote(gbr.tpePars._3) + "," + makeTpeInst(outerColTpe,gbr.tpePars._3) + "]", stream)
          emitGroupByCommonVals(o, in, gbr.cond, gbr.tpePars, stream)
          stream.println("    def valFunc = " + makeOpImplMethodNameWithArgs(o, "_map"))
          stream.println("    def zero = " + makeOpImplMethodNameWithArgs(o, "_zero"))
          stream.println("    def reduceFunc = " + makeOpImplMethodNameWithArgs(o, "_reduce"))

          val outDcArg = if (getHkTpe(in.tpe) == getHkTpe(outerColTpe)) "in" else "null.asInstanceOf["+repify(tpeInst(outerColTpe, gbr.tpePars._3))+"]"
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,gbr.tpePars._3)) + "("+outDcArg+", len)")

          emitOpNodeFooter(o, stream)
          stream.println()
      }
    }
    stream.println()
  }

  def emitNodeConstructors(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    // methods that construct nodes
    for (o <- uniqueOps if !Impls(o).isInstanceOf[Redirect]) {
      stream.println("  " + makeOpMethodSignature(o) + " = {")
      val summary = scala.collection.mutable.ArrayBuffer[String]()

      if (Impls(o).isInstanceOf[CodeGen]) {
        for (arg <- o.args) {
          arg match {
            case Def(Arg(name, f@Def(FTpe(args,ret,freq)), d2)) =>
              stream.println()
              for (s <- args if s.tpe != byName) {
                emitWithIndent("val " + boundArgName(arg,s) + " = fresh[" + quote(s.tpe) + "]", stream, 4)
              }
              val fargs = if (!isThunk(f)) makeArgs(args, a => boundArgName(arg,a)) else ""
              emitWithIndent("val b_" + name + " = reifyEffects(" + name + fargs + ")", stream, 4)
              emitWithIndent("val sb_" + name + " = summarizeEffects(b_" + name + ")", stream, 4)
              summary += "sb_"+name
            case _ =>
          }
        }
      }

      def summarizeEffects(s: scala.collection.mutable.ArrayBuffer[String]): String = {
        if (s.length == 0) ""
        else {
          val rest = summarizeEffects(s.tail)
          if (rest == "") s.head
          else s.head + " andThen ((" + rest + " andThen " + s.head + ").star)"
        }
      }

      val hasEffects = summary.length > 0

      // composites, getters and setters are currently inlined
      // in the future, to support pattern matching and optimization, we should implement these as abstract IR nodes and use lowering transformers
      Impls(o) match {
        case c:Composite => emitWithIndent(makeOpImplMethodNameWithArgs(o), stream, 4)
        case g@Getter(structArgIndex,field) =>
          val struct = o.args.apply(structArgIndex)
          val fieldTpe = DataStructs(getHkTpe(struct.tpe)).fields.find(t => t._1 == field).get.tpe
          emitWithIndent("field["+quote(fieldTpe)+"]("+inline(o,quotedArg(struct.name),quoteLiteral)+",\""+field+"\")", stream, 4)
        case s@Setter(structArgIndex,field,value) =>
          val struct = o.args.apply(structArgIndex)
          val fieldTpe = DataStructs(getHkTpe(struct.tpe)).fields.find(t => t._1 == field).get.tpe
          emitWithIndent("field_update["+quote(fieldTpe)+"]("+inline(o,quotedArg(struct.name),quoteLiteral)+",\""+field+"\","+inline(o,value,quoteLiteral)+")", stream, 4)
        case _:GroupBy | _:GroupByReduce =>
          emitWithIndent("val keys = " + makeEffectAnnotation(o.effect,o) + "(" + makeOpNodeName(o, "Keys") + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o) + ")", stream, 4)
          emitWithIndent("val index = " + makeEffectAnnotation(o.effect,o) + "(" + makeOpNodeName(o, "Index") + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o) + ")", stream, 4)
          emitWithIndent("val values = " + makeEffectAnnotation(o.effect,o) + "(" + makeOpNodeName(o) + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o) + ")", stream, 4)
          emitWithIndent(makeEffectAnnotation(pure,o) + "(DeliteMapNewImm(keys, values, index, darray_length(values)))", stream, 4)
        case _ if hasEffects =>
          // if (o.effect != simple) { err("don't know how to generate non-simple effects with functions") }
          val prologue = if (o.effect == simple) " andAlso Simple()" else ""
          val args = "(" + o.args.flatMap(a => a match {
            case Def(Arg(name, f@Def(FTpe(args,ret,freq)), d2)) =>
              val freshSyms = if (isThunk(f)) Nil else args.map(b => boundArgName(a,b))
              ("b_" + name) :: freshSyms
            case Def(Arg(name, _, _)) => List(name)
          }).mkString(",") + ")"
          emitWithIndent(makeEffectAnnotation(simple,o) + "(" + makeOpNodeName(o) + makeTpePars(o.tpePars) + args + makeOpImplicitArgs(o) + ", " + summarizeEffects(summary) + prologue + ")", stream, 4)
        case _ =>
          emitWithIndent(makeEffectAnnotation(o.effect,o) + "(" + makeOpNodeName(o) + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o) + ")", stream, 4)
      }

      emitWithIndent("}", stream, 2)
    }
  }

  def emitSyms(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    if (uniqueOps.exists(o => o.args.exists(t => t match { case Def(Arg(_, Def(FTpe(a,b,freq)), _)) => true; case _ => false}))) {
      emitBlockComment("Syms", stream, indent=2)

      var symsBuf      = "override def syms(e: Any): List[Sym[Any]] = e match {" + nl
      var boundSymsBuf = "override def boundSyms(e: Any): List[Sym[Any]] = e match {" + nl
      var symsFreqBuf  = "override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {" + nl

      def makeSym(o: Rep[DSLOp], wrap: String, addFreq: Boolean = false) = {
        val symsArgs = o.args.collect {
          case t@Def(Arg(name, Def(FTpe(args,ret,freq)), d2)) => (freq,name,args.filterNot(_.tpe == byName).map(a => boundArgName(t,a)))
          case Def(Arg(name,tpe,d2)) => (normal,name,Nil)
        }
        if (hasFuncArgs(o)) {
          var symsArgsStr = symsArgs.map { case (f,name,bound) => wrap + (if (addFreq) makeFrequencyAnnotation(f) else "") + "(" + name + ")" }.mkString(":::")
          if (wrap == "effectSyms") symsArgsStr += " ::: List(" + symsArgs.flatMap(t => t._3.map(e => e + ".asInstanceOf[Sym[Any]]")).mkString(",") + ")"
          "    case " + makeOpSimpleNodeNameWithArgs(o) + " => " + symsArgsStr + nl
        }
        else ""
      }

      for (o <- uniqueOps if Impls(o).isInstanceOf[CodeGen]) {
        symsBuf += makeSym(o, "syms")
        boundSymsBuf += makeSym(o, "effectSyms")
        symsFreqBuf += makeSym(o, "", addFreq = true)
      }

      symsBuf      += "    case _ => super.syms(e)" + nl + "  }"
      boundSymsBuf += "    case _ => super.boundSyms(e)" + nl + "  }"
      symsFreqBuf  += "    case _ => super.symsFreq(e)" + nl + "  }"

      for (buf <- List(symsBuf,boundSymsBuf,symsFreqBuf)) emitWithIndent(buf,stream,2)
    }
  }

  def emitAliasInfo(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    if (uniqueOps.exists(o => hasIRNode(o) && o.aliasHint != nohint)) {
      emitBlockComment("Aliases / Sharing", stream, indent=2)

      var aliasBuf    = "override def aliasSyms(e: Any): List[Sym[Any]] = e match {" + nl
      var containBuf  = "override def containSyms(e: Any): List[Sym[Any]] = e match {" + nl
      var extractBuf  = "override def extractSyms(e: Any): List[Sym[Any]] = e match {" + nl
      var copyBuf     = "override def copySyms(e: Any): List[Sym[Any]] = e match {" + nl

      def makeAliasAnnotation(o: Rep[DSLOp], args: List[Int]) = {
        val rhs = if (args == Nil) "Nil" else args.map(i => "syms(" + o.args.apply(i).name + ")").mkString(":::")        // TODO
        "    case " + makeOpSimpleNodeNameWithArgs(o) + " => " + rhs + nl
      }

      def makeAllAliasAnnotations(o: Rep[DSLOp], aliasSyms: Option[List[Int]], containSyms: Option[List[Int]], extractSyms: Option[List[Int]], copySyms: Option[List[Int]]) = {
        aliasSyms.foreach   { l => aliasBuf   += makeAliasAnnotation(o,l) }
        containSyms.foreach { l => containBuf += makeAliasAnnotation(o,l) }
        extractSyms.foreach { l => extractBuf += makeAliasAnnotation(o,l) }
        copySyms.foreach    { l => copyBuf    += makeAliasAnnotation(o,l) }
      }

      for (o <- uniqueOps if hasIRNode(o) && o.aliasHint != nohint) {
        o.aliasHint match {
          case AliasCopies(z) =>
            if (o.args.length == z.length) makeAllAliasAnnotations(o, Some(Nil), Some(Nil), Some(Nil), Some(z)) // == aliasesNone
            else makeAllAliasAnnotations(o, None, None, None, Some(z))

          case AliasInfo(al,co,ex,cp) => makeAllAliasAnnotations(o,al,co,ex,cp)
        }
      }

      aliasBuf   += "    case _ => super.aliasSyms(e)" + nl + "  }"
      containBuf += "    case _ => super.containSyms(e)" + nl + "  }"
      extractBuf += "    case _ => super.extractSyms(e)" + nl + "  }"
      copyBuf    += "    case _ => super.copySyms(e)" + nl + "  }"

      for (buf <- List(aliasBuf,containBuf,extractBuf,copyBuf)) emitWithIndent(buf,stream,2)
    }
  }

  def emitMirrors(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    emitBlockComment("Mirroring", stream, indent=2)
    stream.println("  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit pos: SourceContext): Exp[A] = (e match {")

    for (o <- uniqueOps) {
      // helpful identifiers
      val xformArgs = "(" + o.args.zipWithIndex.flatMap(t => t._1 match {
        case Def(Arg(name, f@Def(FTpe(args,ret,freq)), d2)) if Impls(o).isInstanceOf[CodeGen] && !isThunk(f) => "f("+opArgPrefix+t._2+")" :: args.map(a => boundArgAnonName(t._1,a,t._2) /*+ ".asInstanceOf[Sym[Any]]"*/)
        // -- workaround for apparent scalac bug (GADT skolem type error), with separate cases for regular tpes and function tpes. this may be too restrictive and miss cases we haven't seen yet that also trigger the bug.
        case Def(Arg(name, f@Def(FTpe(args,ret,freq)), d2)) if isTpePar(o.retTpe) && !isThunk(f) && args.forall(a => a.tpe == o.retTpe || !isTpePar(a.tpe)) && ret == o.retTpe => List("f("+opArgPrefix+t._2+".asInstanceOf[" + repify(f).replaceAllLiterally(repify(o.retTpe), "Rep[A]") + "])")
        case Def(Arg(name, tpe, d2)) if !isFuncArg(t._1) && isTpePar(o.retTpe) && tpe.tpePars.length == 1 && tpe.tpePars.apply(0) == o.retTpe => List("f("+opArgPrefix+t._2+".asInstanceOf[Rep[" + tpe.name + "[A]]])")
        // -- end workaround
        case Def(Arg(name, _, _)) => List("f("+opArgPrefix+t._2+")")
      }).mkString(",") + ")"

      val implicits = o.tpePars.flatMap(t => t.ctxBounds.map(b => makeTpeClsPar(b,t))) ++
                      o.implicitArgs.map { a =>
                         val argName = opIdentifierPrefix + "." + a.name
                         if (isTpeClass(a.tpe)) asTpeClass(a.tpe).signature.wrapper.getOrElse("") + "(" + argName + ")" else argName
                      }
      val implicitsWithParens = if (implicits.length == 0) "" else implicits.mkString("(",",",")")

      def emitDelitePureMirror(suffix: String = "") {
        stream.print("    case " + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithAnonArgs(o, suffix) + " => ")
        stream.print("reflectPure(new { override val original = Some(f," + opIdentifierPrefix + ") } with " + makeOpNodeName(o, suffix) + xformArgs + implicitsWithParens + ")")
        stream.println("(mtype(manifest[A]), pos)")
      }

      def emitDeliteEffectfulMirror(suffix: String = "") {
        stream.print("    case Reflect(" + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithAnonArgs(o, suffix) + ", u, es) => reflectMirrored(Reflect(new { override val original = Some(f," + opIdentifierPrefix + ") } with " + makeOpNodeName(o, suffix) + xformArgs + implicitsWithParens)
        stream.print(", mapOver(f,u), f(es)))")
        stream.println("(mtype(manifest[A]), pos)")
      }

      Impls(o) match {
        case codegen:CodeGen =>
          stream.print("    case " + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithAnonArgs(o) + " => ")
          // pure version with no func args uses smart constructor
          if (!hasFuncArgs(o)) {
            stream.print(makeOpMethodName(o) + xformArgs)
            if (needOverload(o) || implicits.length > 0) {
              stream.print("(")
              if (implicits.length > 0) stream.print(implicits.mkString(","))
              // we may need to supply an explicit Overload parameter for the smart constructor
              // relies on conventions established in implicitArgsWithOverload (e.g., guaranteed to always be the last implicit)
              if (needOverload(o)) {
                val overload = implicitArgsWithOverload(o).last
                if (implicits.length > 0) stream.print(",")
                stream.print(quote(overload))
              }
              stream.println(")")
            }
          }
          else {
            stream.print("reflectPure(" + makeOpNodeName(o) + xformArgs + implicitsWithParens + ")")
            stream.println("(mtype(manifest[A]), pos)")
          }

          // effectful version
          stream.print("    case Reflect(" + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithAnonArgs(o) + ", u, es) => reflectMirrored(Reflect(" + makeOpNodeName(o) + xformArgs + implicitsWithParens)
          stream.print(", mapOver(f,u), f(es)))")
          stream.println("(mtype(manifest[A]), pos)")
        case _:GroupBy | _:GroupByReduce =>
          emitDelitePureMirror("Keys")
          emitDeliteEffectfulMirror("Keys")
          emitDelitePureMirror("Index")
          emitDeliteEffectfulMirror("Index")
          emitDelitePureMirror()
          emitDeliteEffectfulMirror()
        case _:DeliteOpType =>
          emitDelitePureMirror()
          emitDeliteEffectfulMirror()
        case _ => // no mirror
      }
    }
    stream.println("    case _ => super.mirror(e, f)")
    stream.println("  }).asInstanceOf[Exp[A]]")
  }

  def emitDeliteCollection(opsGrp: DSLOps, stream: PrintWriter) {
    val classes = opsGrpTpes(opsGrp)
    if (classes.length > 0 && classes.exists(c => ForgeCollections.contains(c))) {
      emitBlockComment("Delite collection", stream, indent=2)
      var dcSizeStream = ""
      var dcApplyStream = ""
      var dcUpdateStream = ""
      var dcParallelizationStream = ""
      var dcSetLogicalSizeStream = ""
      var dcAppendableStream = ""
      var dcAppendStream = ""
      var dcAllocStream = ""
      var dcCopyStream = ""

      var firstCol = true
      var firstBuf = true
      for (tpe <- classes if ForgeCollections.contains(tpe)) {
        val dc = ForgeCollections(tpe)
        val isTpe = "is"+tpe.name
        def asTpe = "as"+tpe.name
        val colTpe = makeTpeInst(tpe, tpePar("A"))
        val a = if (dc.tpeArg.tp.runtimeClass == classOf[TypePar]) "A" else quote(dc.tpeArg) // hack!

        stream.println("  def " + isTpe + "Tpe(x: Manifest[_])(implicit ctx: SourceContext) = isSubtype(x.erasure,classOf["+makeTpeInst(tpe, tpePar("_"))+"])")
        stream.println("  def " + isTpe + "[A](x: Exp[DeliteCollection[A]])(implicit ctx: SourceContext) = " + isTpe + "Tpe(x.tp)")
        stream.println("  def " + asTpe + "[A](x: Exp[DeliteCollection[A]])(implicit ctx: SourceContext) = x.asInstanceOf[Exp["+colTpe+"]]")
        stream.println()

        val prefix = if (firstCol) "    if " else "    else if "
        dcSizeStream +=   prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dc.size) + "(" + asTpe + "(x))" + nl
        dcApplyStream +=  prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dc.apply) + "(" + asTpe + "(x), n).asInstanceOf[Exp[A]]" + nl
        dcUpdateStream += prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dc.update) + "(" + asTpe + "(x), n, y.asInstanceOf[Exp["+a+"]])" + nl

        if (dc.isInstanceOf[ParallelCollectionBuffer]) {
          val dcb = dc.asInstanceOf[ParallelCollectionBuffer]
          val appendTpe = if (isTpePar(dcb.appendable.args.apply(2).tpe)) "y" else "y.asInstanceOf[Exp["+quote(dcb.appendable.args.apply(2).tpe)+"]]"
          val allocTpePars = instAllocReturnTpe(dcb.alloc, ephemeralTpe(tpe.name, List(tpePar("A"))), tpePar("A"))
          val prefix = if (firstBuf) "    if " else "    else if "

          // dcParallelizationStream += "    if (" + isTpe + "(x)) " + makeOpMethodName(dcb.parallelization) + "(" + asTpe + "(x), hasConditions)" + nl
          dcParallelizationStream += prefix + "(" + isTpe + "(x)) { if (hasConditions) ParSimpleBuffer else ParFlat } // TODO: always generating this right now" + nl
          dcSetLogicalSizeStream +=  prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dcb.setSize) + "(" + asTpe + "(x), y)" + nl
          dcAppendableStream +=      prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dcb.appendable) + "(" + asTpe + "(x), i, "+appendTpe+")" + nl
          dcAppendStream +=          prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dcb.append) + "(" + asTpe + "(x), i, "+appendTpe+")" + nl
          dcAllocStream +=           prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dcb.alloc) + makeTpePars(allocTpePars) + "(" + asTpe + "(x), size).asInstanceOf[Exp[CA]]" + nl
          dcCopyStream +=            prefix + "(" + isTpe + "(src) && " + isTpe + "(dst)) " + makeOpMethodName(dcb.copy) + "(" + asTpe + "(src), srcPos, " + asTpe + "(dst), dstPos, size)" + nl
          firstBuf = false
        }
        firstCol = false
      }

      if (!firstCol) {
        stream.println("  override def dc_size[A:Manifest](x: Exp[DeliteCollection[A]])(implicit ctx: SourceContext) = {")
        stream.print(dcSizeStream)
        stream.println("    else super.dc_size(x)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_apply[A:Manifest](x: Exp[DeliteCollection[A]], n: Exp[Int])(implicit ctx: SourceContext) = {")
        stream.print(dcApplyStream)
        stream.println("    else super.dc_apply(x,n)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_update[A:Manifest](x: Exp[DeliteCollection[A]], n: Exp[Int], y: Exp[A])(implicit ctx: SourceContext) = {")
        stream.print(dcUpdateStream)
        stream.println("    else super.dc_update(x,n,y)")
        stream.println("  }")
        stream.println()
      }
      if (!firstBuf) {
        stream.println("  override def dc_parallelization[A:Manifest](x: Exp[DeliteCollection[A]], hasConditions: Boolean)(implicit ctx: SourceContext) = {")
        stream.print(dcParallelizationStream)
        stream.println("    else super.dc_parallelization(x, hasConditions)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_set_logical_size[A:Manifest](x: Exp[DeliteCollection[A]], y: Exp[Int])(implicit ctx: SourceContext) = {")
        stream.print(dcSetLogicalSizeStream)
        stream.println("    else super.dc_set_logical_size(x,y)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_appendable[A:Manifest](x: Exp[DeliteCollection[A]], i: Exp[Int], y: Exp[A])(implicit ctx: SourceContext) = {")
        stream.print(dcAppendableStream)
        stream.println("    else super.dc_appendable(x,i,y)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_append[A:Manifest](x: Exp[DeliteCollection[A]], i: Exp[Int], y: Exp[A])(implicit ctx: SourceContext) = {")
        stream.print(dcAppendStream)
        stream.println("    else super.dc_append(x,i,y)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_alloc[A:Manifest,CA<:DeliteCollection[A]:Manifest](x: Exp[CA], size: Exp[Int])(implicit ctx: SourceContext) = {")
        stream.print(dcAllocStream)
        stream.println("    else super.dc_alloc[A,CA](x,size)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_copy[A:Manifest](src: Exp[DeliteCollection[A]], srcPos: Exp[Int], dst: Exp[DeliteCollection[A]], dstPos: Exp[Int], size: Exp[Int])(implicit ctx: SourceContext) = {")
        stream.print(dcCopyStream)
        stream.println("    else super.dc_copy(src,srcPos,dst,dstPos,size)")
        stream.println("  }")
      }
    }
  }

  def emitStructMethods(opsGrp: DSLOps, stream: PrintWriter) {
    def wrapManifest(t: Rep[DSLType]): String = t match {
      case Def(TpeInst(Def(Tpe(name,args,stage)), ps)) if ps != Nil =>
        val tpeArgIndices = ps.map(p => if (isTpePar(p)) 1 else 0).scan(0)(_+_).drop(1).map(_ - 1)
        "makeManifest(classOf[" + name + ps.map(a => "_").mkString("[",",","]") + "], " + (ps.zipWithIndex.map(t => if (isTpePar(t._1)) "m.typeArguments("+tpeArgIndices(t._2)+")" else wrapManifest(t._1))).mkString("List(",",","))")
      case _ =>
        "manifest[" + quote(t) + "]"
    }

    val classes = opsGrpTpes(opsGrp)
    if (classes.length > 0 && classes.exists(c => DataStructs.contains(c))) {
      emitBlockComment("Delite struct", stream, indent=2)
      var structStream = ""
      var first = true
      for (tpe <- classes if DataStructs.contains(tpe)) {
        val d = DataStructs(tpe)
        val fields = d.fields.zipWithIndex.map { case ((fieldName,fieldType),i) => ("\""+fieldName+"\"", if (isTpePar(fieldType)) "m.typeArguments("+i+")" else wrapManifest(fieldType)) }
        val erasureCls = tpe.name + (if (!tpe.tpePars.isEmpty) "[" + tpe.tpePars.map(t => "_").mkString(",") + "]" else "")
        val prefix = if (first) "    if " else "    else if "
        structStream += prefix + "(m.erasure == classOf["+erasureCls+"]) Some((classTag(m), collection.immutable.List("+fields.mkString(",")+")))" + nl
        first = false
      }

      if (!first) {
        stream.println("  override def unapplyStructType[T:Manifest]: Option[(StructTag[T], List[(String,Manifest[_])])] = {")
        stream.println("    val m = manifest[T]")
        stream.print(structStream)
        stream.println("    else super.unapplyStructType(m)")
        stream.println("  }")
      }

      var dcDataFieldStream = ""
      var dcSizeFieldStream = ""
      first = true
      for (tpe <- classes if DataStructs.contains(tpe) && ForgeCollections.contains(tpe)) {
        val d = DataStructs(tpe)
        val dc = ForgeCollections(tpe)
        val arrayFields = d.fields.filter(t => getHkTpe(t._2) == MArray)
        if (arrayFields.length != 1 && Config.verbosity > 0) {
          warn("could not infer data field for struct " + tpe.name)
        }
        val sizeField = Impls.get(dc.size).map(i => if (i.isInstanceOf[Getter]) i.asInstanceOf[Getter].field else None)
        if (sizeField.isEmpty && Config.verbosity > 0) {
          warn("could not infer size field for struct " + tpe.name)
        }
        if (arrayFields.length == 1 && sizeField.isDefined) {
          val isTpe = "is"+tpe.name+"Tpe"
          val prefix = if (first) "    if " else "    else if "
          dcDataFieldStream += prefix + "("+isTpe+"(x)) \"" + arrayFields(0)._1 + "\"" + nl
          dcSizeFieldStream += prefix + "("+isTpe+"(x)) \"" + sizeField.get + "\"" + nl
          first = false
        }
      }

      if (!first) {
        stream.println("  override def dc_data_field(x: Manifest[_]) = {")
        stream.print(dcDataFieldStream)
        stream.println("    else super.dc_data_field(x)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_size_field(x: Manifest[_]) = {")
        stream.print(dcSizeFieldStream)
        stream.println("    else super.dc_size_field(x)")
        stream.println("  }")
      }
    }
  }

  def emitOpCodegen(opsGrp: DSLOps, stream: PrintWriter) {
    val rules = unique(opsGrp.ops).map(o => (o,Impls(o))).filter(_._2.isInstanceOf[CodeGen])
    if (rules.length > 0){
      emitBlockComment("Code generators", stream)
      stream.println()
      val save = activeGenerator
      for (g <- generators) {
        activeGenerator = g
        val generatorRules = rules.flatMap{case (o,i) => i.asInstanceOf[CodeGen].decls.collect{case (k,r) if (k == g) => (o,r)}}
        if (generatorRules.length > 0) {
          stream.println("trait " + g.name + "Gen" + opsGrp.name + " extends " + g.name + "GenFat {")
          stream.println("  val IR: " + opsGrp.name + "Exp")
          stream.println("  import IR._")
          stream.println()
          stream.println("  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {")
          for ((op,r) <- generatorRules) {
            stream.println("    case " + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithArgs(op) + " => ")
            val body = quote(r.decl).trim.split(nl).toList
            // how do we decide whether to add stream.println?
            def addPrint(s: String) = {
              // hack! (or heuristic, if the glass is half full)
              !s.startsWith("emit")
            }
            val body2 = body map { l => if (addPrint(l)) "stream.print("+l+")" else l }
            // use stream.print, since the new lines from the original interpolated code block are still there
            g match {
              case `$cala` =>
                val result = ("stream.println(\"val \"+quote(sym)+\" = {\")" :: body2) :+ "stream.println(\"}\")"
                result.foreach { line => emitWithIndent(line, stream, 6) }
              case `cuda` | `cpp` =>
                if (op.retTpe == MUnit || op.retTpe == MNothing) {
                  body2.foreach { line => emitWithIndent(line, stream, 6) }
                  emitWithIndent("stream.println(\";\")", stream, 6)
                }
                else {
                  body2.take(body2.length-1).foreach { line => emitWithIndent(line, stream, 6) }
                  emitWithIndent("stream.print(remapWithRef(sym.tp) + \" \" + quote(sym) + \" = \")", stream, 6)
                  emitWithIndent(body2.last, stream, 6)
                  emitWithIndent("stream.println(\";\")", stream, 6)
                }
              case _ => throw new RuntimeException("Not supported codgen:" + g.toString)
            }
            stream.println()
          }
          stream.println("    case _ => super.emitNode(sym, rhs)")
          stream.println("  }")
          stream.println("}")
        }
      }
      activeGenerator = save
    }
  }
}
