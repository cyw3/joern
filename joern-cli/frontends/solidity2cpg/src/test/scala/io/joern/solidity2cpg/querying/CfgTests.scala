package io.joern.solidity2cpg.querying

import io.joern.solidity2cpg.testfixtures.SolidityCodeToCpgFixture
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes.Identifier
import io.shiftleft.semanticcpg.language.{ICallResolver, NoResolve, toNodeTypeStarters, _}
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes._
import io.shiftleft.proto.cpg.Cpg.DispatchTypes
import io.shiftleft.semanticcpg.language._
class CfgTests extends SolidityCodeToCpgFixture {

  override val code: String =
    """
      |pragma solidity ^0.8.0;
      |
      |contract Foo {
      |    event print(uint256 x);
      |    event printString(string x);
      |    function foo(uint256 x, uint256 y) public returns(uint256) {
      |        if (y < 10) {
      |            return 1;
      |        }
      |        if (x < 5) {
      |            sink(x);
      |        }
      |        emit printString("foo");
      |        return 0;
      |    }
      |
      |    function sink(uint256 x) public returns(uint256) {
      |        emit print(x);
      |    }
      |}
    """.stripMargin

  "should find that sink is control dependent on condition" in {
    println(cpg.typeDecl.dotAst.head)
    println(cpg.call("sink").controlledBy.isCall.toSetMutable)
    val controllers = cpg.call("sink").controlledBy.isCall.toSetMutable
    println(controllers.map(_.order))
    controllers.map(_.code) should contain("y < 10")
    controllers.map(_.code) should contain("x < 5")
  }

  // No control structure node on flat ast
  //  "should find that first if controls `sink`" in {
  //    cpg.controlStructure.condition.code("y < 10").controls.isCall.name("sink").l.size shouldBe 1
  //  }

  //  "should find sink(x) does not dominate anything" in {
  //    cpg.call("sink").dominates.l.size shouldBe 0
  //  }

  "should find sink(x) is dominated by `x < 5` and `y < 10`" in {
    cpg.call("sink").dominatedBy.isCall.code.toSetMutable shouldBe Set("x >= 5", "y >= 10")
  }

  //  "should find that println post dominates correct nodes" in {
  //    cpg.call("println").postDominates.size shouldBe 6
  //  }

  //  "should find that method does not post dominate anything" in {
  //    cpg.method("foo").postDominates.l.size shouldBe 0
  //  }

}