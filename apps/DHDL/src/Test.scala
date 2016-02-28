import dhdl.compiler._
import dhdl.library._
import dhdl.shared._

object TestCompiler extends DHDLApplicationCompiler with Test 
object TestInterpreter extends DHDLApplicationInterpreter with Test 

trait Test extends DHDLApplication {
	def printUsage = {
    println("Usage: dotprod")
    exit(-1)
	}
  def main() = {

		val om = OffChipMem[FixPt]("om", 1, 2, 3, 4, 5)
		val bm = BRAM[FixPt](5)
		om.ld(bm, 0, 5)
		assert(bm.ld(4)!=5)

		val a = FixPt(5)
		val b = FixPt(7)
		val r = Reg(a)
		println(a.mkString)
		assert(r.value==a)
		r.write(b)
		assert(r.value==b)
		assert(r.init==a)
		r.reset
		assert(r.value==a)

		val m = BRAM[FixPt](16)
		m.st(3,b)
		assert(m.ld(3)==b)

		val ctr1 = Counter(ctrName="ctr1", max=3)
		val ctr2 = Counter(ctrName="ctr2", max=4)
		val ctrs = CounterChain(ctr1, ctr2)

		assert((a*a*a)==a.pow(3))
		/*
		val accBm = BRAM[FixPt](5)
		BramReduce[FixPt](1, false, ctrs, accBm, (_+_), { case i::_ =>
			(i, j)
		})
		println(accBm.mkString)
		*/


	}
}
