package ppl.dsl.forge
package dsls
package dhdl

trait DHDLMetadata {
  this: DHDLDSL =>

	def importDHDLMetadata () = {
		val T = tpePar("T")

		val RegTpe    = lookupTpe("RegTpe", stage=compile)
    val PipeStyle = lookupTpe("PipeStyle", stage=compile)
    val Reg       = lookupTpe("Reg")
    val Pipeline  = lookupTpe("Pipeline")
    val Idx       = lookupAlias("SInt")

		/* Static multidimension dims and size */
		val MDims = metadata("MDims", "dims" -> SList(SInt))
		val dimsOps = metadata("dimsOf")

		onMeet (MDims) ${ this }
		internal.static (dimsOps) ("update", T, (T, SList(SInt)) :: MUnit, effect = simple) implements
			composite ${ setMetadata($0, MDims($1)) }
		internal.static (dimsOps) ("apply", T, T :: SList(SInt)) implements composite ${ meta[MDims]($0).get.dims }
		internal.direct (dimsOps) ("dimOf", T, (T, SInt) :: SInt) implements composite ${ dimsOf($0).apply($1) }
    internal.direct (dimsOps) ("rankOf", T, T :: SInt) implements composite ${ dimsOf($0).length }

    val sizeOps = metadata("sizeOf")
    internal.static (sizeOps) ("update", T, (T, SInt) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MDims(List($1))) }
    internal.static (sizeOps) ("apply", T, T :: SInt) implements composite ${ dimsOf($0).reduce{_*_} }


    /* Dynamic multidimension size */
    val MDynamicSize = metadata("MDynamicSize", "dims" -> SList(Idx))
    val dynamicSizeOps = metadata("symDimsOf")

    onMeet(MDynamicSize) ${ this }
    internal.static (dynamicSizeOps) ("update", T, (T, SList(Idx)) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MDynamicSize($1)) }
    internal.static (dynamicSizeOps) ("apply", T, T :: SList(Idx)) implements composite ${ meta[MDynamicSize]($0).get.dims }

		/* Name of a node */
		val MName = metadata("MName", "name" -> SString)
		val nameOps = metadata("nameOf")
		onMeet (MName) ${ this }
		internal.static (nameOps) ("update", T, (T, SString) :: MUnit, effect = simple) implements
			composite ${ setMetadata($0, MName($1)) }
		internal.static (nameOps) ("apply", T, T :: SString) implements composite ${ meta[MName]($0).get.name }

		/* Is Double Buffer */
		val MDblBuf = metadata("MDblBuf", "isDblBuf" -> SBoolean)
		val dblBufOps = metadata("isDblBuf")
		onMeet (MDblBuf) ${ this }
		internal.static (dblBufOps) ("update", T, (T, SBoolean) :: MUnit, effect = simple) implements
			composite ${ setMetadata($0, MDblBuf($1)) }
		internal.direct (dblBufOps) ("apply", T, T :: SBoolean) implements composite ${ meta[MDblBuf]($0).get.isDblBuf }

		/* Register Type  */
		val MRegTpe = metadata("MRegTpe", "regTpe" -> RegTpe)
		val regTpeOps = metadata("regType")
		onMeet (MRegTpe) ${ this }
		internal.static (regTpeOps) ("update", T, (T, RegTpe) :: MUnit, effect = simple) implements
			composite ${ setMetadata($0, MRegTpe($1)) }
		internal.static (regTpeOps) ("apply", T, T :: RegTpe) implements composite ${ meta[MRegTpe]($0).get.regTpe }

    /* Register Initial Value */
    val MRegInit = metadata("MRegInit", "value" -> MAny)
    val regReset = metadata("resetValue")
    onMeet (MRegInit) ${ this }
    internal.static (regReset) ("update", T, (Reg(T), T) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MRegInit($1)) }
    internal.static (regReset) ("apply", T, Reg(T) :: T) implements
      composite ${ meta[MRegInit]($0).get.value.asInstanceOf[Rep[T]] }

		/* Parallelization Factor  */
		val MPar = metadata("MPar", "par" -> SInt)
		val parOps = metadata("par")
		//TODO:
		onMeet (MPar) ${ this }
		internal.static (parOps) ("update", T, (T, SInt) :: MUnit, effect = simple) implements
			composite ${ setMetadata($0, MPar($1)) }
		internal.static (parOps) ("apply", T, T :: SInt) implements composite ${ meta[MPar]($0).get.par }

		/* Number of Banks  */
		val MBank = metadata("MBank", "nBanks" -> SInt)
		val bankOps = metadata("banks")
		//TODO:
		onMeet (MBank) ${ this }
		internal.static (bankOps) ("update", T, (T, SInt) :: MUnit, effect = simple) implements
			composite ${ setMetadata($0, MBank($1)) }
		internal.static (bankOps) ("apply", T, T :: SInt) implements composite ${ meta[MBank]($0).get.nBanks }

    /* Pipeline style */
    val MPipeType = metadata("MPipeType", "tpe" -> PipeStyle)
    val styleOps = metadata("styleOf")
    onMeet (MPipeType) ${ this }
    internal.static (styleOps) ("update", Nil, (Pipeline, PipeStyle) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MPipeType($1)) }
    internal.static (styleOps) ("apply", Nil, Pipeline :: PipeStyle) implements composite ${ meta[MPipeType]($0).get.tpe }


    /* Range is single dimension */
    val MUnitRange = metadata("MUnitRange", "isUnit" -> SBoolean)
    val unitOps = metadata("isUnit")
    onMeet (MUnitRange) ${ this }
    internal.static (unitOps) ("update", Nil, (MAny, SBoolean) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MUnitRange($1)) }
    internal.static (unitOps) ("apply", Nil, MAny :: SBoolean) implements composite ${ meta[MUnitRange]($0).get.isUnit }
  }
}
