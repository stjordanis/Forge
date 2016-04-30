package ppl.dsl.forge
package dsls
package dhdl

import core.{ForgeApplication,ForgeApplicationRunner}
import scala.virtualization.lms.internal.GenericCodegen

object DHDLDSLRunner extends ForgeApplicationRunner with DHDLDSL

trait DHDLDSL extends ForgeApplication
  with DHDLMath with DHDLMisc with DHDLTypes with DHDLMemories
  with DHDLControllers with DHDLMetadata with DHDLEnums with DHDLSugar with TupleJunk
  with DHDLGlobalAnalysis
  with DHDLBoundAnalysis {

  def dslName = "DHDL"

  override def addREPLOverride = false
  override def clearTraversals = true

  lazy val S = tpePar("S")
  lazy val I = tpePar("I")
  lazy val F = tpePar("F")
  lazy val G = tpePar("G")
  lazy val E = tpePar("E")

  object UnstagedNumerics {
    lazy val prims = List(SInt, SLong, SFloat, SDouble)
    lazy val types = List("Int", "Long", "Float", "Double")

    def foreach(func: (Rep[DSLType],String) => Unit) = {
      for (i <- 0 until prims.length) { func(prims(i),types(i)) }
    }
  }

  def specification() = {
    // No fusion in DHDL (for now)
    disableFusion()

    val T = tpePar("T")

    // --- Primitive Types
    val Bit = tpe("Bit")
    val FixPt = tpe("FixPt", (S,I,F))    // sign, integer, and fraction
    val FltPt = tpe("FltPt", (G,E))      // significand and exponent
    primitiveTypes :::= List(Bit, FixPt, FltPt)

    // --- Type parameters
    val Signed = tpe("Signed", stage=compile)
    val Unsign = tpe("Unsign", stage=compile)
    (0 to 64).foreach{i => tpe("B" + i, stage=compile) } // B0 - B64

    // --- Common Type Aliases
    // Add more as needed
    val B0 = lookupTpe("B0", compile)
    val B5 = lookupTpe("B5", compile)
    val B8 = lookupTpe("B8", compile)
    val B11 = lookupTpe("B11", compile)
    val B24 = lookupTpe("B24", compile)
    val B32 = lookupTpe("B32", compile)
    val B53 = lookupTpe("B53", compile)

    val SInt32 = tpeAlias("SInt", FixPt(Signed, B32, B0))  // Note: This is not a scala Int, this is a signed int!
    val UInt32 = tpeAlias("UInt", FixPt(Unsign, B32, B0))
    val Half   = tpeAlias("Half", FltPt(B11, B5))
    val Flt    = tpeAlias("Flt",  FltPt(B24, B8))
    val Dbl    = tpeAlias("Dbl",  FltPt(B53, B11))

    val Index  = tpeAlias("Index", FixPt(Signed, B32, B0))

    // --- Memory Types
    val OffChip = tpe("OffChipMem", T)
    val Tile    = tpe("Tile", T)
    val BRAM    = tpe("BRAM", T)
    val Reg     = tpe("Reg", T)
    primitiveTypes :::= List(OffChip, BRAM, Reg)


    // --- State Machine Types
    val Counter = tpe("Counter")
    val CounterChain = tpe("CounterChain")
    val Pipeline = tpe("Pipeline")
    primitiveTypes :::= List(Counter, CounterChain, Pipeline)

    // --- Other Types
    val Indices   = tpe("Indices")
    val LoopRange = tpe("LoopRange")
    val Range     = tpe("Range")
    primitiveTypes :::= List(Indices)

    noInfixList :::= List(":=", "**", "as", "to", "rst")

    // Scala.scala imports
    importTuples()
    importStrings()

    // DSL spec imports
    importSugar()
    importDHDLTypes()
    importDHDLEnums()
    importDHDLMetadata()

    importDHDLMath()
    importDHDLMemories()
    importDHDLControllers()

    importDHDLMisc()
    importTupleTypeClassInstances()


    // --- Traversals
    val StageAnalyzer = analyzer("Stage", isExtern=true)
    val GlobalAnalyzer = analyzer("Global")
    val BoundAnalyzer = analyzer("Bound", isIterative=false)
    val DSE = traversal("DSE", isExtern=true)
    val AreaAnalyzer = analyzer("Area", isExtern=true)
    val LatencyAnalyzer = analyzer("Latency", isExtern=true)

    val ConstantFolding = traversal("ConstantFolding", isExtern=true)
    val ControlSignalAnalyzer = analyzer("ControlSignal", isExtern=true)
    val ParameterAnalyzer = analyzer("Parameter",isExtern=true)
    val ParSetter = traversal("ParSetter",isExtern=true)
    val MetaPipeRegInsertion = traversal("MetaPipeRegInsertion",isExtern=true)

    importGlobalAnalysis()
    importBoundAnalysis()

    schedule(StageAnalyzer)
    //schedule(GlobalAnalyzer)
    schedule(DSE)

    // --- Post Parameter Selection
    //schedule(AreaAnalyzer)
    //schedule(LatencyAnalyzer)
    schedule(BoundAnalyzer)
    schedule(ConstantFolding)
    schedule(MetaPipeRegInsertion)

    schedule(ControlSignalAnalyzer)
    schedule(ParSetter)

    //schedule(IRPrinterPlus)

    // External groups
    extern(grp("ControllerTemplate"), targets = List($cala, dot, maxj))
    extern(grp("MemoryTemplate"), targets = List($cala, dot, maxj), withTypes = true)
    extern(metadata("TypeInspection"), targets = List(maxj))
		()
	}
}