package scala.meta.internal.dotty

import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch
import scala.meta.pc.DefinitionResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import scala.meta.pc.OffsetParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.DiagnosticSeverity
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import java.io.File
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import scala.io.Codec
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.SymDenotations._
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.NameKinds._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import dotty.tools.dotc.util.ParsedComment
import dotty.tools.dotc.util.Signatures
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.parsing.Parsers.Parser
import dotty.tools.dotc.reporting.diagnostic.Message
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.reporting.diagnostic.MessageContainer
import dotty.tools.io.VirtualFile
import dotty.tools.dotc.interfaces.{Diagnostic => DottyDiagnostic}
import dotty.tools.dotc.ast.tpd
import java.{util => ju}
import scala.meta.pc.VirtualFileParams
import scala.collection.JavaConverters._
import scala.language.implicitConversions

class ScalaPresentationCompiler(
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil
) extends PresentationCompiler {

  def this() = this(Nil, Nil)

  import InteractiveDriver._

  private var currentDriver: Option[InteractiveDriver] = None

  def driver: InteractiveDriver = {
    currentDriver.getOrElse {
      this.synchronized {
        val defaultFlags = List("-color:never")
        val settings =
          options ::: defaultFlags ::: "-classpath" :: classpath.mkString(
            File.pathSeparator
          ) :: Nil
        val driver = new InteractiveDriver(settings)
        currentDriver = Some(driver)
        driver
      }
    }
  }

  def complete(params: OffsetParams): CompletableFuture[CompletionList] = {
    CompletableFuture.completedFuture {
      val uri = params.uri
      val message = driver.run(uri, params.text)
      implicit def ctx: Context = driver.currentCtx

      val pos = sourcePosition(driver, params, uri)
      val items = driver.compilationUnits.get(uri) match {
        case Some(unit) =>
          Completion.completions(pos)(ctx.fresh.setCompilationUnit(unit))._2
        case None => Nil
      }

      new CompletionList(
        /*isIncomplete = */ false,
        items.map(completionItem).asJava
      )
    }
  }

  def definition(params: OffsetParams): CompletableFuture[DefinitionResult] =
    CompletableFuture.completedFuture {
      implicit def ctx: Context = driver.currentCtx
      val uri = params.uri
      driver.run(uri, params.text)
      val pos = sourcePosition(driver, params, uri)
      val path = Interactive.pathTo(driver.openedTrees(uri), pos)
      val definitions = Interactive.findDefinitions(path, pos, driver).toList

      new DefinitionResultImpl(
        "",
        definitions.flatMap(d => location(d.namePos)).asJava
      )
    }

  def shutdown(): Unit = this.synchronized {
    currentDriver = None
  }

  def restart(): Unit = {
    this.synchronized {
      currentDriver = None
      driver
    }
  }

  def diagnosticsForDebuggingPurposes(): ju.List[String] = List[String]().asJava

  // TODO NOT IMPLEMENTED
  def semanticdbTextDocument(
      filename: String,
      code: String
  ): CompletableFuture[Array[Byte]] = {
    CompletableFuture.completedFuture(
      List[Byte]().toArray
    )
  }

  // TODO NOT IMPLEMENTED
  def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] = {
    CompletableFuture.completedFuture(
      null
    )
  }

  // TODO NOT IMPLEMENTED
  def foldingRange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[FoldingRange]] = {
    CompletableFuture.completedFuture(
      List.empty[FoldingRange].asJava
    )
  }

  // TODO NOT IMPLEMENTED
  def documentSymbols(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[DocumentSymbol]] = {
    CompletableFuture.completedFuture(
      List.empty[DocumentSymbol].asJava
    )
  }

  // TODO NOT IMPLEMENTED
  def onTypeFormatting(
      params: DocumentOnTypeFormattingParams,
      source: String
  ): CompletableFuture[ju.List[TextEdit]] =
    CompletableFuture.completedFuture(
      List.empty[TextEdit].asJava
    )

  // TODO NOT IMPLEMENTED
  def rangeFormatting(
      params: DocumentRangeFormattingParams,
      source: String
  ): CompletableFuture[ju.List[TextEdit]] = {
    CompletableFuture.completedFuture(
      List.empty[TextEdit].asJava
    )
  }

  // TODO NOT IMPLEMENTED
  def autoImports(
      file: String,
      params: scala.meta.pc.OffsetParams
  ): CompletableFuture[
    ju.List[scala.meta.pc.AutoImportsResult]
  ] = {
    CompletableFuture.completedFuture(
      List.empty[scala.meta.pc.AutoImportsResult].asJava
    )
  }

  def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    CompletableFuture.completedFuture(
      List.empty[TextEdit].asJava
    )
  }

  def hover(params: OffsetParams): CompletableFuture[ju.Optional[Hover]] =
    CompletableFuture.completedFuture {
      val uri = params.uri
      implicit def ctx: Context = driver.currentCtx

      val pos = sourcePosition(driver, params, uri)
      val trees = driver.openedTrees(uri)
      val path = Interactive.pathTo(trees, pos)
      val tp = Interactive.enclosingType(trees, pos)
      val tpw = tp.widenTermRefExpr

      if (tp.isError || tpw == NoType)
        ju.Optional.empty()
      else {
        Interactive.enclosingSourceSymbols(path, pos) match {
          case Nil =>
            ju.Optional.empty()
          case symbols =>
            val docComments = symbols.flatMap(ParsedComment.docOf)
            val content = hoverContent(Some(tpw.show), docComments)
            ju.Optional.of(new Hover(content, null))
        }
      }
    }

  def newInstance(
      buildTargetIdentifier: String,
      classpath: ju.List[Path],
      options: ju.List[String]
  ): PresentationCompiler = {
    new ScalaPresentationCompiler(
      classpath = classpath.asScala.toSeq,
      options = options.asScala.toList
    )
  }

  def signatureHelp(params: OffsetParams): CompletableFuture[SignatureHelp] =
    CompletableFuture.completedFuture {
      val uri = params.uri
      implicit def ctx: Context = driver.currentCtx

      val pos = sourcePosition(driver, params, uri)
      val trees = driver.openedTrees(uri)
      val path =
        Interactive.pathTo(trees, pos).dropWhile(!_.isInstanceOf[tpd.Apply])

      val (paramN, callableN, alternatives) =
        Signatures.callInfo(path, pos.span)
      val signatureInfos = alternatives.flatMap(Signatures.toSignature)

      new SignatureHelp(
        signatureInfos.map(signatureToSignatureInformation).asJava,
        callableN,
        paramN
      )
    }

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[Diagnostic]] = {
    CompletableFuture.completedFuture {
      val uri = params.uri
      val sourceFile = toSource(uri, params.text)
      // we need to have a separate reporter otherwise errors are not cleared
      val parsingReporter = new StoreReporter(null)
      val freshContext = driver.currentCtx.fresh.setReporter(parsingReporter)
      val parser = new Parser(sourceFile)(freshContext)
      parser.parse()
      val diags = parsingReporter.allErrors
      diags.flatMap(diagnostic).asJava
    }
  }

  override def didClose(uri: URI): Unit = {
    driver.close(uri)
  }

  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler = this

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler = this

  override def withScheduledExecutorService(
      scheduledExecutorService: ScheduledExecutorService
  ): PresentationCompiler = this

  def withSearch(search: SymbolSearch): PresentationCompiler = {
    this
  }

  private def location(p: SourcePosition): Option[Location] = {
    for {
      uri <- toUriOption(p.source)
      r <- range(p)
    } yield new Location(uri.toString, r)
  }

  private def sourcePosition(
      driver: InteractiveDriver,
      params: OffsetParams,
      uri: URI
  ): SourcePosition = {
    val source = driver.openedFiles(uri)
    val p = Spans.Span(params.offset)
    new SourcePosition(source, p)
  }

  private def range(p: SourcePosition): Option[Range] = {
    if (p.exists) {
      Some(
        new Range(
          new Position(
            p.startLine,
            p.startColumn
          ),
          new Position(p.endLine, p.endColumn)
        )
      )
    } else {
      None
    }
  }

  private def completionItem(
      completion: Completion
  )(implicit ctx: Context): CompletionItem = {
    def completionItemKind(
        sym: Symbol
    )(implicit ctx: Context): CompletionItemKind = {
      if (sym.is(Package) || sym.is(Module))
        CompletionItemKind.Module // No CompletionItemKind.Package (https://github.com/Microsoft/language-server-protocol/issues/155)
      else if (sym.isConstructor)
        CompletionItemKind.Constructor
      else if (sym.isClass)
        CompletionItemKind.Class
      else if (sym.is(Mutable))
        CompletionItemKind.Variable
      else if (sym.is(Method))
        CompletionItemKind.Method
      else
        CompletionItemKind.Field
    }
    val item = new CompletionItem(completion.label)
    item.setDetail(completion.description)

    val documentation = for {
      sym <- completion.symbols
      doc <- ParsedComment.docOf(sym)
    } yield doc

    if (documentation.nonEmpty) {
      item.setDocumentation(hoverContent(None, documentation))
    }

    // item.setDeprecated(completion.symbols.forall(_.isDeprecated))
    completion.symbols.headOption
      .foreach(s => item.setKind(completionItemKind(s)))
    item
  }

  private def hoverContent(
      typeInfo: Option[String],
      comments: List[ParsedComment]
  )(implicit ctx: Context): MarkupContent = {
    val buf = new StringBuilder
    typeInfo.foreach { info =>
      buf.append(s"""```scala
                    |$info
                    |```
                    |""".stripMargin)
    }
    comments.foreach { comment =>
      buf.append(comment.renderAsMarkdown)
    }

    markupContent(buf.toString)
  }

  private def markupContent(content: String): MarkupContent = {
    if (content.isEmpty)
      null
    else {
      val markup = new MarkupContent
      markup.setKind("markdown")
      markup.setValue(content.trim)
      markup
    }
  }

  def signatureToSignatureInformation(
      signature: Signatures.Signature
  ): SignatureInformation = {
    val paramInfoss = signature.paramss.map(_.map(paramToParameterInformation))
    val paramLists = signature.paramss
      .map { paramList =>
        val labels = paramList.map(_.show)
        val prefix = if (paramList.exists(_.isImplicit)) "implicit " else ""
        labels.mkString(prefix, ", ", "")
      }
      .mkString("(", ")(", ")")
    val tparamsLabel =
      if (signature.tparams.isEmpty) ""
      else signature.tparams.mkString("[", ", ", "]")
    val returnTypeLabel = signature.returnType.map(t => s": $t").getOrElse("")
    val label = s"${signature.name}$tparamsLabel$paramLists$returnTypeLabel"
    val documentation = signature.doc.map(markupContent)
    val sig = new SignatureInformation(label)
    sig.setParameters(paramInfoss.flatten.asJava)
    documentation.foreach(sig.setDocumentation(_))
    sig
  }

  /** Convert `param` to `ParameterInformation` */
  private def paramToParameterInformation(
      param: Signatures.Param
  ): ParameterInformation = {
    val documentation = param.doc.map(markupContent)
    val info = new ParameterInformation(param.show)
    documentation.foreach(info.setDocumentation(_))
    info
  }

  private def diagnostic(mc: MessageContainer): Option[Diagnostic] =
    if (!mc.pos.exists)
      None // diagnostics without positions are not supported: https://github.com/Microsoft/language-server-protocol/issues/249
    else {
      def severity(level: Int): DiagnosticSeverity = {
        level match {
          case DottyDiagnostic.INFO =>
            DiagnosticSeverity.Information
          case DottyDiagnostic.WARNING =>
            DiagnosticSeverity.Warning
          case DottyDiagnostic.ERROR =>
            DiagnosticSeverity.Error
        }
      }

      val message = mc.contained()
      val code = message.errorId.errorNumber.toString
      range(mc.pos).map(r =>
        new Diagnostic(
          r,
          mc.message,
          severity(mc.level),
          /*source =*/ "",
          code
        )
      )
    }

  case class DefinitionResultImpl(
      symbol: String,
      locations: java.util.List[Location]
  ) extends DefinitionResult

  object DefinitionResultImpl {
    def empty: DefinitionResult = {
      DefinitionResultImpl("", java.util.Collections.emptyList())
    }
  }

  private def toSource(uri: URI, sourceCode: String): SourceFile = {
    val path = Paths.get(uri)
    val virtualFile = new VirtualFile(path.getFileName.toString, path.toString)
    val writer = new BufferedWriter(
      new OutputStreamWriter(virtualFile.output, "UTF-8")
    )
    writer.write(sourceCode)
    writer.close()
    new SourceFile(virtualFile, Codec.UTF8)
  }

}
