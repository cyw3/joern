package io.joern.ghidra2cpg.passes.mips

import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.{EdgeTypes, PropertyNames}
import io.shiftleft.passes.{ConcurrentWriterCpgPass, SimpleCpgPass}
import io.shiftleft.semanticcpg.language._
import org.slf4j.{Logger, LoggerFactory}

import io.shiftleft.codepropertygraph.generated.nodes._

class LoHiPass(cpg: Cpg) extends ConcurrentWriterCpgPass[(Call, Call)](cpg) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  override def generateParts(): Array[(Call, Call)] = {
    val readFromLoHiRegsRegex = "_?(mflo|mfhi).*"

    val writeToLoHiRegsRegex = "_?(div|divu|mul|mult).*"

    def from = cpg.call.code(writeToLoHiRegsRegex).l

    def to = cpg.call.code(readFromLoHiRegsRegex).l

    // TODO: improve the pair creation to take into consideration register value overwrites.
    // e.g. in pseudo-assembly: div X Y; nop; nop; mflo Z; nop; nop; div P Q; mflo R
    // it should not add REACHING_DEF edges from X to R and Y to R, but it currently does
    for (fromNode <- from; toNode <- to) yield (fromNode, toNode)
  }.toArray

  override def runOnPart(diffGraph: DiffGraphBuilder, pair: (Call, Call)): Unit = {
    diffGraph.addEdge(pair._1, pair._2, EdgeTypes.REACHING_DEF, PropertyNames.VARIABLE, pair._1.code)
  }
}
