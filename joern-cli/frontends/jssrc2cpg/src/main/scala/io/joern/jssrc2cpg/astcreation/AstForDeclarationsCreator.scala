package io.joern.jssrc2cpg.astcreation

import io.joern.jssrc2cpg.datastructures.BlockScope
import io.joern.jssrc2cpg.datastructures.MethodScope
import io.joern.jssrc2cpg.datastructures.ScopeType
import io.joern.jssrc2cpg.parser.BabelAst._
import io.joern.jssrc2cpg.parser.BabelNodeInfo
import io.joern.jssrc2cpg.passes.Defines
import io.joern.x2cpg.Ast
import io.joern.x2cpg.datastructures.Stack._
import io.shiftleft.codepropertygraph.generated.EdgeTypes
import io.shiftleft.codepropertygraph.generated.nodes.{NewImport, NewNamespaceBlock}
import io.shiftleft.codepropertygraph.generated.DispatchTypes
import ujson.Value

import scala.util.Try

trait AstForDeclarationsCreator { this: AstCreator =>

  private val DEFAULTS_KEY    = "default"
  private val EXPORT_KEYWORD  = "exports"
  private val REQUIRE_KEYWORD = "require"
  private val IMPORT_KEYWORD  = "import"

  private def hasNoName(json: Value): Boolean = !hasKey(json, "id") || json("id").isNull

  protected def codeForBabelNodeInfo(obj: BabelNodeInfo): Seq[String] = {
    val codes = obj.node match {
      case Identifier                                 => Seq(obj.code)
      case NumericLiteral                             => Seq(obj.code)
      case StringLiteral                              => Seq(obj.code)
      case AssignmentExpression                       => Seq(code(obj.json("left")))
      case ClassDeclaration                           => Seq(code(obj.json("id")))
      case TSTypeAliasDeclaration                     => Seq(code(obj.json("id")))
      case TSInterfaceDeclaration                     => Seq(code(obj.json("id")))
      case TSEnumDeclaration                          => Seq(code(obj.json("id")))
      case TSModuleDeclaration                        => Seq(code(obj.json("id")))
      case TSDeclareFunction if hasNoName(obj.json)   => Seq.empty
      case TSDeclareFunction                          => Seq(code(obj.json("id")))
      case FunctionDeclaration if hasNoName(obj.json) => Seq.empty
      case FunctionDeclaration                        => Seq(code(obj.json("id")))
      case FunctionExpression if hasNoName(obj.json)  => Seq.empty
      case FunctionExpression                         => Seq(code(obj.json("id")))
      case ClassExpression if hasNoName(obj.json)     => Seq.empty
      case ClassExpression                            => Seq(code(obj.json("id")))
      case VariableDeclaration                        => obj.json("declarations").arr.toSeq.map(d => code(d("id")))
      case ObjectExpression                           => obj.json("properties").arr.toSeq.map(code)
      case MemberExpression                           => Seq(code(obj.json("property")))
      case JSXElement                                 => Seq.empty
      case _ =>
        notHandledYet(obj, "Retrieve code")
        Seq.empty
    }
    codes.map(_.replace("...", ""))
  }

  private def createExportCallAst(name: String, exportName: String, declaration: BabelNodeInfo): Ast = {
    val exportCallAst = if (name == DEFAULTS_KEY) {
      createIndexAccessCallAst(
        createIdentifierNode(exportName, declaration),
        createLiteralNode(s"\"$DEFAULTS_KEY\"", Some(Defines.STRING), declaration.lineNumber, declaration.columnNumber),
        declaration.lineNumber,
        declaration.columnNumber
      )
    } else {
      createFieldAccessCallAst(
        createIdentifierNode(exportName, declaration),
        createFieldIdentifierNode(name, declaration.lineNumber, declaration.columnNumber),
        declaration.lineNumber,
        declaration.columnNumber
      )
    }
    exportCallAst
  }

  private def createExportAssignmentCallAst(name: String, exportCallAst: Ast, declaration: BabelNodeInfo): Ast =
    createAssignmentCallAst(
      exportCallAst,
      Ast(createIdentifierNode(name, declaration)),
      s"${codeOf(exportCallAst.nodes.head)} = $name",
      declaration.lineNumber,
      declaration.columnNumber
    )

  private def extractDeclarationsFromExportDecl(declaration: BabelNodeInfo, key: String): Option[(Ast, Seq[String])] =
    safeObj(declaration.json, key)
      .map { d =>
        val nodeInfo    = createBabelNodeInfo(d)
        val ast         = astForNodeWithFunctionReferenceAndCall(d)
        val defaultName = codeForNodes(ast.nodes.toSeq)
        val codes       = codeForBabelNodeInfo(nodeInfo)
        val names       = if (codes.isEmpty) defaultName.toSeq else codes
        (ast, names)
      }

  private def extractExportFromNameFromExportDecl(declaration: BabelNodeInfo): String =
    safeObj(declaration.json, "source")
      .map { d => s"_${stripQuotes(code(d))}" }
      .getOrElse(EXPORT_KEYWORD)

  private def cleanImportName(name: String): String = if (name.contains("/")) {
    val stripped = name.stripSuffix("/")
    stripped.substring(stripped.lastIndexOf("/") + 1)
  } else name

  private def createAstForFrom(fromName: String, declaration: BabelNodeInfo): Ast = {
    if (fromName == EXPORT_KEYWORD) {
      Ast()
    } else {
      val strippedCode = cleanImportName(fromName).stripPrefix("_")
      val id           = createIdentifierNode(s"_$strippedCode", declaration)
      val localNode    = createLocalNode(id.code, Defines.ANY)
      scope.addVariable(id.code, localNode, BlockScope)
      scope.addVariableReference(id.code, id)
      diffGraph.addEdge(localAstParentStack.head, localNode, EdgeTypes.AST)

      val sourceCallArgNode =
        createLiteralNode(s"\"${fromName.stripPrefix("_")}\"", None, declaration.lineNumber, declaration.columnNumber)
      val sourceCall = createCallNode(
        s"$REQUIRE_KEYWORD(${sourceCallArgNode.code})",
        REQUIRE_KEYWORD,
        DispatchTypes.STATIC_DISPATCH,
        declaration.lineNumber,
        declaration.columnNumber
      )
      val sourceAst =
        createCallAst(sourceCall, List(Ast(sourceCallArgNode)))
      val assigmentCallAst =
        createAssignmentCallAst(
          Ast(id),
          sourceAst,
          s"var ${codeOf(id)} = ${codeOf(sourceAst.nodes.head)}",
          declaration.lineNumber,
          declaration.columnNumber
        )
      assigmentCallAst
    }
  }

  protected def astForExportNamedDeclaration(declaration: BabelNodeInfo): Ast = {
    val specifiers = declaration
      .json("specifiers")
      .arr
      .toList
      .map { spec =>
        if (createBabelNodeInfo(spec).node == ExportNamespaceSpecifier) {
          val exported = createBabelNodeInfo(spec("exported"))
          (None, Some(exported))
        } else {
          val exported = createBabelNodeInfo(spec("exported"))
          val local = if (hasKey(spec, "local")) {
            createBabelNodeInfo(spec("local"))
          } else {
            exported
          }
          (Some(local), Some(exported))
        }
      }

    val exportName      = extractExportFromNameFromExportDecl(declaration)
    val fromAst         = createAstForFrom(exportName, declaration)
    val declAstAndNames = extractDeclarationsFromExportDecl(declaration, "declaration")
    val declAsts = declAstAndNames.toList.map { case (ast, names) =>
      ast +: names.map { name =>
        if (exportName != EXPORT_KEYWORD)
          diffGraph.addNode(createDependencyNode(name, exportName.stripPrefix("_"), REQUIRE_KEYWORD))
        val exportCallAst = createExportCallAst(name, exportName, declaration)
        createExportAssignmentCallAst(name, exportCallAst, declaration)
      }
    }

    val specifierAsts = specifiers.map {
      case (Some(name), Some(alias)) =>
        if (exportName != EXPORT_KEYWORD)
          diffGraph.addNode(createDependencyNode(alias.code, exportName.stripPrefix("_"), REQUIRE_KEYWORD))
        val exportCallAst = createExportCallAst(alias.code, exportName, declaration)
        createExportAssignmentCallAst(name.code, exportCallAst, declaration)
      case (None, Some(alias)) =>
        diffGraph.addNode(createDependencyNode(alias.code, exportName.stripPrefix("_"), REQUIRE_KEYWORD))
        val exportCallAst = createExportCallAst(alias.code, EXPORT_KEYWORD, declaration)
        createExportAssignmentCallAst(exportName, exportCallAst, declaration)
    }

    val asts = fromAst +: (specifierAsts ++ declAsts.flatten)
    setArgIndices(asts)
    blockAst(createBlockNode(declaration), asts)
  }

  protected def astForExportAssignment(assignment: BabelNodeInfo): Ast = {
    val expressionAstWithNames = extractDeclarationsFromExportDecl(assignment, "expression")
    val declAsts = expressionAstWithNames.map { case (ast, names) =>
      ast +: names.map { name =>
        val exportCallAst = createExportCallAst(name, EXPORT_KEYWORD, assignment)
        createExportAssignmentCallAst(name, exportCallAst, assignment)
      }
    }

    val asts = declAsts.toList.flatten
    setArgIndices(asts)
    blockAst(createBlockNode(assignment), asts)
  }

  protected def astForExportDefaultDeclaration(declaration: BabelNodeInfo): Ast = {
    val exportName      = extractExportFromNameFromExportDecl(declaration)
    val declAstAndNames = extractDeclarationsFromExportDecl(declaration, "declaration")

    val declAsts = declAstAndNames.map { case (ast, names) =>
      ast +: names.map { name =>
        val exportCallAst = createExportCallAst(DEFAULTS_KEY, exportName, declaration)
        createExportAssignmentCallAst(name, exportCallAst, declaration)
      }
    }

    val asts = declAsts.toList.flatten
    setArgIndices(asts)
    blockAst(createBlockNode(declaration), asts)
  }

  protected def astForExportAllDeclaration(declaration: BabelNodeInfo): Ast = {
    val exportName = extractExportFromNameFromExportDecl(declaration)
    val depGroupId = stripQuotes(code(declaration.json("source")))
    val name       = cleanImportName(depGroupId)
    if (exportName != EXPORT_KEYWORD) {
      diffGraph.addNode(createDependencyNode(name, depGroupId, REQUIRE_KEYWORD))
    }

    val fromCallAst       = createAstForFrom(exportName, declaration)
    val exportCallAst     = createExportCallAst(name, EXPORT_KEYWORD, declaration)
    val assignmentCallAst = createExportAssignmentCallAst(s"_$name", exportCallAst, declaration)

    val asts = List(fromCallAst, assignmentCallAst)
    setArgIndices(asts)
    blockAst(createBlockNode(declaration), asts)
  }

  protected def astForVariableDeclaration(declaration: BabelNodeInfo): Ast = {
    val kind = declaration.json("kind").str
    val scopeType = if (kind == "let") {
      BlockScope
    } else {
      MethodScope
    }
    val declAsts = declaration.json("declarations").arr.toList.map(astForVariableDeclarator(_, scopeType, kind))
    declAsts match {
      case head :: tail =>
        setArgIndices(declAsts)
        tail.foreach { declAst =>
          declAst.root.foreach(diffGraph.addEdge(localAstParentStack.head, _, EdgeTypes.AST))
          Ast.storeInDiffGraph(declAst, diffGraph)
        }
        head
      case Nil => Ast()
    }
  }

  private def handleRequireCallForDependencies(declarator: BabelNodeInfo, lhs: Value, rhs: Value): Unit = {
    val rhsCode  = code(rhs)
    val groupId  = rhsCode.substring(rhsCode.indexOf(s"$REQUIRE_KEYWORD(") + 9, rhsCode.indexOf(")") - 1)
    val nodeInfo = createBabelNodeInfo(lhs)
    val names = nodeInfo.node match {
      case ArrayPattern  => nodeInfo.json("elements").arr.toList.map(code)
      case ObjectPattern => nodeInfo.json("properties").arr.toList.map(code)
      case _             => List(code(lhs))
    }
    names.foreach { name =>
      val dependencyNode = createDependencyNode(name, groupId, REQUIRE_KEYWORD)
      diffGraph.addNode(dependencyNode)
      val importNode = createImportNodeAndAttachToAst(declarator, groupId, name)
      diffGraph.addEdge(importNode, dependencyNode, EdgeTypes.IMPORTS)
    }
  }

  private def astForVariableDeclarator(declarator: Value, scopeType: ScopeType, kind: String): Ast = {
    val id             = createBabelNodeInfo(declarator("id"))
    val init           = Try(createBabelNodeInfo(declarator("init"))).toOption
    val declaratorCode = s"$kind ${code(declarator)}"

    val typeFullName = init match {
      case Some(f @ BabelNodeInfo(_: FunctionLike, _, _, _, _, _, _)) =>
        val (_, methodFullName) = calcMethodNameAndFullName(f)
        methodFullName
      case _ => init.map(typeFor).getOrElse(Defines.ANY)
    }

    val localNode = createLocalNode(id.code, typeFullName)
    scope.addVariable(id.code, localNode, scopeType)
    diffGraph.addEdge(localAstParentStack.head, localNode, EdgeTypes.AST)

    if (init.isEmpty) {
      Ast()
    } else {
      val sourceAst = init.get match {
        case requireCall if requireCall.code.startsWith(s"$REQUIRE_KEYWORD(") =>
          handleRequireCallForDependencies(createBabelNodeInfo(declarator), id.json, init.get.json)
          astForNodeWithFunctionReference(requireCall.json)
        case initExpr =>
          astForNodeWithFunctionReference(initExpr.json)
      }
      val nodeInfo = createBabelNodeInfo(id.json)
      nodeInfo.node match {
        case ObjectPattern | ArrayPattern =>
          astForDeconstruction(nodeInfo, sourceAst, declaratorCode)
        case _ =>
          val destAst = id.node match {
            case Identifier => astForIdentifier(id, Some(typeFullName))
            case _          => astForNode(id.json)
          }

          val assigmentCallAst =
            createAssignmentCallAst(
              destAst,
              sourceAst,
              declaratorCode,
              line = line(declarator),
              column = column(declarator)
            )
          assigmentCallAst
      }
    }
  }

  protected def astForTSImportEqualsDeclaration(impDecl: BabelNodeInfo): Ast = {
    val name          = impDecl.json("id")("name").str
    val referenceNode = createBabelNodeInfo(impDecl.json("moduleReference"))
    val referenceName = referenceNode.node match {
      case TSExternalModuleReference => referenceNode.json("expression")("value").str
      case _                         => referenceNode.code
    }
    val dependencyNode = createDependencyNode(name, referenceName, IMPORT_KEYWORD)
    diffGraph.addNode(dependencyNode)
    val importNode = createImportNodeAndAttachToAst(impDecl, referenceName, name)
    diffGraph.addEdge(importNode, dependencyNode, EdgeTypes.IMPORTS)
    astForRequireCallFromImport(name, None, referenceName, isImportN = false, impDecl)
  }

  private def astForRequireCallFromImport(
    name: String,
    alias: Option[String],
    from: String,
    isImportN: Boolean,
    nodeInfo: BabelNodeInfo
  ): Ast = {
    val destName  = alias.getOrElse(name)
    val destNode  = createIdentifierNode(destName, nodeInfo)
    val localNode = createLocalNode(destName, Defines.ANY)
    scope.addVariable(destName, localNode, BlockScope)
    scope.addVariableReference(destName, destNode)
    diffGraph.addEdge(localAstParentStack.head, localNode, EdgeTypes.AST)

    val destAst           = Ast(destNode)
    val sourceCallArgNode = createLiteralNode(s"\"$from\"", None, nodeInfo.lineNumber, nodeInfo.columnNumber)
    val sourceCall = createCallNode(
      s"$REQUIRE_KEYWORD(${sourceCallArgNode.code})",
      REQUIRE_KEYWORD,
      DispatchTypes.DYNAMIC_DISPATCH,
      nodeInfo.lineNumber,
      nodeInfo.columnNumber
    )

    val receiverNode = createIdentifierNode(REQUIRE_KEYWORD, nodeInfo)
    val thisNode     = createIdentifierNode("this", nodeInfo)
    scope.addVariableReference(thisNode.name, thisNode)
    val callAst = createCallAst(
      sourceCall,
      List(Ast(sourceCallArgNode)),
      receiver = Some(Ast(receiverNode)),
      base = Some(Ast(thisNode))
    )
    val sourceAst = if (isImportN) {
      val fieldAccessCall = createFieldAccessCallAst(
        callAst,
        createFieldIdentifierNode(name, nodeInfo.lineNumber, nodeInfo.columnNumber),
        nodeInfo.lineNumber,
        nodeInfo.columnNumber
      )
      fieldAccessCall
    } else {
      callAst
    }
    val assigmentCallAst =
      createAssignmentCallAst(
        destAst,
        sourceAst,
        s"var ${codeOf(destAst.nodes.head)} = ${codeOf(sourceAst.nodes.head)}",
        nodeInfo.lineNumber,
        nodeInfo.columnNumber
      )
    assigmentCallAst
  }

  protected def astForImportDeclaration(impDecl: BabelNodeInfo): Ast = {
    val source     = impDecl.json("source")("value").str
    val specifiers = impDecl.json("specifiers").arr

    if (specifiers.isEmpty) {
      val dependencyNode = createDependencyNode(source, source, IMPORT_KEYWORD)
      diffGraph.addNode(dependencyNode)
      val importNode = createImportNodeAndAttachToAst(impDecl, source, source)
      diffGraph.addEdge(importNode, dependencyNode, EdgeTypes.IMPORTS)
      astForRequireCallFromImport(source, None, source, isImportN = false, impDecl)
    } else {
      val specs = impDecl.json("specifiers").arr.toList
      val depNodes = specs.map { importSpecifier =>
        val importedName   = importSpecifier("local")("name").str
        val importNode     = createImportNodeAndAttachToAst(impDecl, source, importedName)
        val dependencyNode = createDependencyNode(importedName, source, IMPORT_KEYWORD)
        diffGraph.addEdge(importNode, dependencyNode, EdgeTypes.IMPORTS)
        dependencyNode
      }
      depNodes.foreach(diffGraph.addNode)
      val requireCalls = specs.map { importSpecifier =>
        val name = importSpecifier("local")("name").str
        val isImportN = createBabelNodeInfo(importSpecifier).node match {
          case ImportSpecifier => true
          case _               => false
        }
        val (alias, reqName) = if (hasKey(importSpecifier, "imported")) {
          (Some(name), importSpecifier("imported")("name").str)
        } else {
          (None, name)
        }
        astForRequireCallFromImport(reqName, alias, source, isImportN = isImportN, impDecl)
      }
      if (requireCalls.isEmpty) {
        Ast()
      } else if (requireCalls.size == 1) {
        requireCalls.head
      } else {
        blockAst(createBlockNode(impDecl), requireCalls)
      }
    }
  }

  private def createImportNodeAndAttachToAst(
    impDecl: BabelNodeInfo,
    importedEntity: String,
    importedAs: String
  ): NewImport = {
    val impNode = createImportNode(impDecl, Some(importedEntity).filter(_.trim.nonEmpty), importedAs)
    methodAstParentStack.collectFirst { case namespaceBlockNode: NewNamespaceBlock =>
      diffGraph.addEdge(namespaceBlockNode, impNode, EdgeTypes.AST)
    }
    impNode
  }

  private def convertDestructingObjectElement(element: BabelNodeInfo, key: BabelNodeInfo, localTmpName: String): Ast = {
    val valueAst = astForNode(element.json)

    val localNode = createLocalNode(element.code, Defines.ANY)
    diffGraph.addEdge(localAstParentStack.head, localNode, EdgeTypes.AST)
    scope.addVariable(element.code, localNode, MethodScope)

    val fieldAccessTmpNode = createIdentifierNode(localTmpName, element)
    val keyNode            = createFieldIdentifierNode(key.code, key.lineNumber, key.columnNumber)
    val accessAst = createFieldAccessCallAst(fieldAccessTmpNode, keyNode, element.lineNumber, element.columnNumber)
    createAssignmentCallAst(
      valueAst,
      accessAst,
      s"${codeOf(valueAst.nodes.head)} = ${codeOf(accessAst.nodes.head)}",
      element.lineNumber,
      element.columnNumber
    )
  }

  private def convertDestructingArrayElement(element: BabelNodeInfo, index: Int, localTmpName: String): Ast = {
    val valueAst = astForNode(element.json)

    val localNode = createLocalNode(element.code, Defines.ANY)
    diffGraph.addEdge(localAstParentStack.head, localNode, EdgeTypes.AST)
    scope.addVariable(element.code, localNode, MethodScope)

    val fieldAccessTmpNode = createIdentifierNode(localTmpName, element)
    val keyNode =
      createLiteralNode(index.toString, Some(Defines.NUMBER), element.lineNumber, element.columnNumber)
    val accessAst = createIndexAccessCallAst(fieldAccessTmpNode, keyNode, element.lineNumber, element.columnNumber)
    createAssignmentCallAst(
      valueAst,
      accessAst,
      s"${codeOf(valueAst.nodes.head)} = ${codeOf(accessAst.nodes.head)}",
      element.lineNumber,
      element.columnNumber
    )
  }

  private def convertDestructingArrayElementWithDefault(
    element: BabelNodeInfo,
    index: Int,
    localTmpName: String
  ): Ast = {
    val rhsElement = element.json("right")
    val rhsAst     = astForNodeWithFunctionReference(rhsElement)

    val lhsElement = element.json("left")
    val nodeInfo   = createBabelNodeInfo(lhsElement)
    val lhsAst = nodeInfo.node match {
      case ObjectPattern | ArrayPattern =>
        val sourceAst = astForNodeWithFunctionReference(createBabelNodeInfo(rhsElement).json)
        astForDeconstruction(nodeInfo, sourceAst, element.code)
      case _ => astForNodeWithFunctionReference(lhsElement)
    }

    val testAst = {
      val fieldAccessTmpNode = createIdentifierNode(localTmpName, element)
      val keyNode =
        createLiteralNode(index.toString, Some(Defines.NUMBER), element.lineNumber, element.columnNumber)
      val accessAst =
        createIndexAccessCallAst(fieldAccessTmpNode, keyNode, element.lineNumber, element.columnNumber)
      val voidCallNode = createVoidCallNode(element.lineNumber, element.columnNumber)
      createEqualsCallAst(accessAst, Ast(voidCallNode), element.lineNumber, element.columnNumber)
    }
    val falseAst = {
      val fieldAccessTmpNode = createIdentifierNode(localTmpName, element)
      val keyNode =
        createLiteralNode(index.toString, Some(Defines.NUMBER), element.lineNumber, element.columnNumber)
      createIndexAccessCallAst(fieldAccessTmpNode, keyNode, element.lineNumber, element.columnNumber)
    }
    val ternaryNodeAst =
      createTernaryCallAst(testAst, rhsAst, falseAst, element.lineNumber, element.columnNumber)
    createAssignmentCallAst(
      lhsAst,
      ternaryNodeAst,
      s"${codeOf(lhsAst.nodes.head)} = ${codeOf(ternaryNodeAst.nodes.head)}",
      element.lineNumber,
      element.columnNumber
    )
  }

  private def convertDestructingObjectElementWithDefault(
    element: BabelNodeInfo,
    key: BabelNodeInfo,
    localTmpName: String
  ): Ast = {
    val rhsElement = element.json("right")
    val rhsAst     = astForNodeWithFunctionReference(rhsElement)

    val lhsElement = element.json("left")
    val nodeInfo   = createBabelNodeInfo(lhsElement)
    val lhsAst = nodeInfo.node match {
      case ObjectPattern | ArrayPattern =>
        val sourceAst = astForNodeWithFunctionReference(createBabelNodeInfo(rhsElement).json)
        astForDeconstruction(nodeInfo, sourceAst, element.code)
      case _ => astForNodeWithFunctionReference(lhsElement)
    }

    val testAst = {
      val fieldAccessTmpNode = createIdentifierNode(localTmpName, element)
      val keyNode            = createFieldIdentifierNode(key.code, key.lineNumber, key.columnNumber)
      val accessAst =
        createFieldAccessCallAst(fieldAccessTmpNode, keyNode, element.lineNumber, element.columnNumber)
      val voidCallNode = createVoidCallNode(element.lineNumber, element.columnNumber)
      createEqualsCallAst(accessAst, Ast(voidCallNode), element.lineNumber, element.columnNumber)
    }
    val falseAst = {
      val fieldAccessTmpNode = createIdentifierNode(localTmpName, element)
      val keyNode            = createFieldIdentifierNode(key.code, key.lineNumber, key.columnNumber)
      createFieldAccessCallAst(fieldAccessTmpNode, keyNode, element.lineNumber, element.columnNumber)
    }
    val ternaryNodeAst =
      createTernaryCallAst(testAst, rhsAst, falseAst, element.lineNumber, element.columnNumber)
    createAssignmentCallAst(
      lhsAst,
      ternaryNodeAst,
      s"${codeOf(lhsAst.nodes.head)} = ${codeOf(ternaryNodeAst.nodes.head)}",
      element.lineNumber,
      element.columnNumber
    )
  }

  private def createParamAst(pattern: BabelNodeInfo, keyName: String, sourceAst: Ast): Ast = {
    val testAst = {
      val lhsNode = createIdentifierNode(keyName, pattern)
      scope.addVariableReference(keyName, lhsNode)
      val rhsNode = createCallNode(
        "void 0",
        "<operator>.void",
        DispatchTypes.STATIC_DISPATCH,
        pattern.lineNumber,
        pattern.columnNumber
      )
      createEqualsCallAst(Ast(lhsNode), Ast(rhsNode), pattern.lineNumber, pattern.columnNumber)
    }

    val falseNode = {
      val initNode = createIdentifierNode(keyName, pattern)
      scope.addVariableReference(keyName, initNode)
      initNode
    }
    createTernaryCallAst(testAst, sourceAst, Ast(falseNode), pattern.lineNumber, pattern.columnNumber)
  }

  protected def astForDeconstruction(
    pattern: BabelNodeInfo,
    sourceAst: Ast,
    code: String,
    paramName: Option[String] = None
  ): Ast = {
    val localTmpName = generateUnusedVariableName(usedVariableNames, "_tmp")

    val blockNode = createBlockNode(pattern, Some(code))
    scope.pushNewBlockScope(blockNode)
    localAstParentStack.push(blockNode)

    val localNode = createLocalNode(localTmpName, Defines.ANY)
    val tmpNode   = createIdentifierNode(localTmpName, pattern)
    diffGraph.addEdge(localAstParentStack.head, localNode, EdgeTypes.AST)
    scope.addVariable(localTmpName, localNode, BlockScope)
    scope.addVariableReference(localTmpName, tmpNode)

    val rhsAssignmentAst = paramName.map(createParamAst(pattern, _, sourceAst)).getOrElse(sourceAst)
    val assignmentTmpCallAst =
      createAssignmentCallAst(
        Ast(tmpNode),
        rhsAssignmentAst,
        s"$localTmpName = ${codeOf(rhsAssignmentAst.nodes.head)}",
        pattern.lineNumber,
        pattern.columnNumber
      )

    val subTreeAsts = pattern.node match {
      case ObjectPattern =>
        pattern.json("properties").arr.toList.map { element =>
          val nodeInfo = createBabelNodeInfo(element)
          nodeInfo.node match {
            case RestElement =>
              val arg1Ast = Ast(createIdentifierNode(localTmpName, nodeInfo))
              astForSpreadOrRestElement(nodeInfo, Some(arg1Ast))
            case _ =>
              val nodeInfo = createBabelNodeInfo(element("value"))
              nodeInfo.node match {
                case Identifier =>
                  convertDestructingObjectElement(nodeInfo, createBabelNodeInfo(element("key")), localTmpName)
                case AssignmentPattern =>
                  convertDestructingObjectElementWithDefault(
                    nodeInfo,
                    createBabelNodeInfo(element("key")),
                    localTmpName
                  )
                case _ => astForNodeWithFunctionReference(nodeInfo.json)
              }
          }
        }
      case ArrayPattern =>
        pattern.json("elements").arr.toList.zipWithIndex.map {
          case (element, index) if !element.isNull =>
            val nodeInfo = createBabelNodeInfo(element)
            nodeInfo.node match {
              case RestElement =>
                val fieldAccessTmpNode = createIdentifierNode(localTmpName, nodeInfo)
                val keyNode =
                  createLiteralNode(index.toString, Some(Defines.NUMBER), nodeInfo.lineNumber, nodeInfo.columnNumber)
                val accessAst =
                  createIndexAccessCallAst(fieldAccessTmpNode, keyNode, nodeInfo.lineNumber, nodeInfo.columnNumber)
                astForSpreadOrRestElement(nodeInfo, Some(accessAst))
              case Identifier =>
                convertDestructingArrayElement(nodeInfo, index, localTmpName)
              case AssignmentPattern =>
                convertDestructingArrayElementWithDefault(nodeInfo, index, localTmpName)
              case _ => astForNodeWithFunctionReference(nodeInfo.json)
            }
          case _ => Ast()
        }
      case _ =>
        List(convertDestructingObjectElement(pattern, pattern, localTmpName))
    }

    val returnTmpNode = createIdentifierNode(localTmpName, pattern)
    scope.popScope()
    localAstParentStack.pop()

    val blockChildren = assignmentTmpCallAst +: subTreeAsts :+ Ast(returnTmpNode)
    setArgIndices(blockChildren)
    Ast(blockNode).withChildren(blockChildren)
  }

}
