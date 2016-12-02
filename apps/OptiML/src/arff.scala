import optiml.compiler._
import optiml.library._
import optiml.shared._

import scala.virtualization.lms.common.Record

object ARFFCompiler extends OptiMLApplicationCompiler with ARFF
object ARFFInterpreter extends OptiMLApplicationInterpreter with ARFF

/**
 * Example program showing how to use OptiML to read ARFF files. 
 *
 * This program is meant to work with the 'iris.arff' dataset. OptiML does not currently
 * support reading arbitrary (no pre-defined schema) ARFF files, as that would result in the
 * loss of static types and potentially degrade performance without improved stage-time support.
 */
 
trait ARFF extends OptiMLApplication {
  def printUsage = {
    println("Usage: arff <iris.arff>")
    exit(-1)
  }
  
  def mySchema(v: Rep[DenseVector[String]]) = 
    Record (
      sepalLength = v(0).toDouble,
      sepalWidth = v(1).toDouble,
      petalLength = v(2).toDouble,
      petalWidth = v(3).toDouble,
      cls = v(4)
    )
  
  def main() = {
    if (args.length < 1) printUsage

    val in = readARFF(args(0), mySchema)
    
    val sep0: Rep[Double] = in(0).sepalLength
    
    println("My first row is: ")
    println("sepalLength: " + in(0).sepalLength + ", sepalWidth: " + in(0).sepalWidth + ", petalLength: " + in(0).petalLength + ", petalWidth: " + in(0).petalWidth  + ", class: " + in(0).cls)
    
    // create a DenseMatrix[Double] out of the first four elements of the schema
    val m = DenseMatrix(in map { row => DenseVector(row.sepalLength, row.sepalWidth, row.petalLength, row.petalWidth) })
    m.sliceRows(0, 10).pprint
  }
}

