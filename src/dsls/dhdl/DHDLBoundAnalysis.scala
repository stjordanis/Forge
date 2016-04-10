package ppl.dsl.forge
package dsls
package dhdl

// Not for general use, only for checking expected number of iterations of loops
trait DHDLBoundAnalysis {
  this: DHDLDSL =>

  def importBoundAnalysis() = {
    val BoundAnalyzer = analyzer("Bound")
    val Prim = lookupGrp("DHDLPrim")
    val Tpes = lookupGrp("Tpes")
    val Ctrl = lookupGrp("BasicCtrl")
    val Lifts = lookupGrp("ConstLifts")
    val Reg = lookupTpe("Reg")

    val BoundAnalysisRules = withAnalyzer(BoundAnalyzer)
    BoundAnalysisRules {
      analyze(Lifts, "constFixPt") using rule ${ bound(lhs) = fixed(implicitly[Numeric[T]].toDouble($0)) }
      analyze(Lifts, "constFltPt") using rule ${ bound(lhs) = fixed(implicitly[Numeric[T]].toDouble($0)) }

      analyze(Tpes, "int_to_fix") using rule ${ bound(lhs) = bound($0) }

      // TODO: These could actually be structs! Handle using normal propagation instead
      analyze(Reg, "reg_new") using rule ${ bound(lhs) = bound($0) }
      analyze(Reg, "reg_read") using rule ${ bound(lhs) = bound($0) }
      analyze(Reg, "reg_write") using rule ${ bound($0) = bound($1) }

      // TODO: assumes values are non-negative (i.e. max(x * y) could actually be min(x) * min(y) for neg. values )
      // Only for use with index calculation right now
      analyze(Prim, "add_fix") using pattern((${Fixed(x)},${Fixed(y)}) -> ${ bound(lhs) = fixed(x + y) })
      analyze(Prim, "add_fix") using pattern((${Fixed(x)},${Exact(y)}) -> ${ bound(lhs) = exact(x + y) })
      analyze(Prim, "add_fix") using pattern((${Exact(x)},${Fixed(y)}) -> ${ bound(lhs) = exact(x + y) })
      analyze(Prim, "add_fix") using pattern((${Exact(x)},${Exact(y)}) -> ${ bound(lhs) = exact(x + y) })
      analyze(Prim, "add_fix") using pattern((${Bound(x)},${Bound(y)}) -> ${ bound(lhs) = x + y })

      analyze(Prim, "sub_fix") using pattern((${Fixed(x)},${Fixed(y)}) -> ${ bound(lhs) = fixed(x - y) })
      analyze(Prim, "sub_fix") using pattern((${Fixed(x)},${Exact(y)}) -> ${ bound(lhs) = exact(x - y) })
      analyze(Prim, "sub_fix") using pattern((${Exact(x)},${Fixed(y)}) -> ${ bound(lhs) = exact(x - y) })
      analyze(Prim, "sub_fix") using pattern((${Exact(x)},${Exact(y)}) -> ${ bound(lhs) = exact(x - y) })
      analyze(Prim, "sub_fix") using pattern((${Bound(x)},${Exact(y)}) -> ${ bound(lhs) = x - y })

      analyze(Prim, "mul_fix") using pattern((${Fixed(x)},${Fixed(y)}) -> ${ bound(lhs) = fixed(x * y) })
      analyze(Prim, "mul_fix") using pattern((${Fixed(x)},${Exact(y)}) -> ${ bound(lhs) = exact(x * y) })
      analyze(Prim, "mul_fix") using pattern((${Exact(x)},${Fixed(y)}) -> ${ bound(lhs) = exact(x * y) })
      analyze(Prim, "mul_fix") using pattern((${Exact(x)},${Exact(y)}) -> ${ bound(lhs) = exact(x * y) })
      analyze(Prim, "mul_fix") using pattern((${Bound(x)},${Bound(y)}) -> ${ bound(lhs) = x * y })

      analyze(Prim, "div_fix") using pattern((${Fixed(x)},${Fixed(y)}) -> ${ bound(lhs) = fixed(x / y) })
      analyze(Prim, "div_fix") using pattern((${Fixed(x)},${Exact(y)}) -> ${ bound(lhs) = exact(x / y) })
      analyze(Prim, "div_fix") using pattern((${Exact(x)},${Fixed(y)}) -> ${ bound(lhs) = exact(x / y) })
      analyze(Prim, "div_fix") using pattern((${Exact(x)},${Exact(y)}) -> ${ bound(lhs) = exact(x / y) })
      analyze(Prim, "div_fix") using pattern((${Bound(x)},${Exact(y)}) -> ${ bound(lhs) = x / y })
    }
  }

}
