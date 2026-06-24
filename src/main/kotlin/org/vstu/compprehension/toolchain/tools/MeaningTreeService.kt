package org.vstu.compprehension.toolchain.tools

import com.fasterxml.jackson.databind.JsonNode
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.apache.jena.rdf.model.ModelFactory
import org.vstu.compprehension.toolchain.Json
import org.vstu.compprehension.toolchain.boolOr
import org.vstu.compprehension.toolchain.child
import org.vstu.compprehension.toolchain.longOrNull
import org.vstu.compprehension.toolchain.rpc.ParamDoc
import org.vstu.compprehension.toolchain.rpc.ResultDoc
import org.vstu.compprehension.toolchain.rpc.RpcCall
import org.vstu.compprehension.toolchain.rpc.RpcMethod
import org.vstu.compprehension.toolchain.rpc.Schemas
import org.vstu.compprehension.toolchain.rpc.ToolModule
import org.vstu.compprehension.toolchain.rpc.invalidParams
import org.vstu.compprehension.toolchain.textOrNull
import org.vstu.meaningtree.Main
import org.vstu.meaningtree.MeaningTree
import org.vstu.meaningtree.languages.LanguageTranslator
import org.vstu.meaningtree.languages.SourceMapGenerator
import org.vstu.meaningtree.languages.configs.Config
import org.vstu.meaningtree.languages.configs.ConfigBuilder
import org.vstu.meaningtree.languages.configs.ConfigParameters
import org.vstu.meaningtree.nodes.Node
import org.vstu.meaningtree.serializers.dot.GraphvizDotSerializer
import org.vstu.meaningtree.serializers.json.JsonDeserializer
import org.vstu.meaningtree.serializers.json.JsonSerializer
import org.vstu.meaningtree.serializers.json.JsonTypeHierarchyBuilder
import org.vstu.meaningtree.serializers.rdf.RDFDeserializer
import org.vstu.meaningtree.serializers.rdf.RDFSerializer
import org.vstu.meaningtree.serializers.xml.XMLDeserializer
import org.vstu.meaningtree.serializers.xml.XMLSerializer
import org.vstu.meaningtree.utils.tokens.Token
import java.io.File
import java.io.Serializable
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path

/**
 * Wraps the `meaning_tree` CLI as JSON-RPC methods (translate, generate, list-langs, node-hierarchy).
 *
 * meaning_tree relies on process-global node/token id counters ([Node.setupId], [Token.setupId]),
 * so translate/generate are serialized under [mtLock] to keep ids deterministic. Other tools still
 * run concurrently.
 */
object MeaningTreeService {

    private val mtLock = Any()

    private val translators: Map<String, Class<out LanguageTranslator>> get() = Main.translators

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val gsonPretty = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    private val serializeFormats = listOf("json", "xml", "dot", "rdf", "rdf-turtle")
    private val deserializeFormats = listOf("json", "xml", "rdf", "rdf-turtle")

    fun module(): ToolModule = ToolModule(
        route = "/rpc/meaning-tree",
        title = "meaning_tree",
        description = "Language-agnostic translation/serialization of source code via the universal Meaning Tree representation.",
        methods = listOf(translate(), generate(), listLangs(), nodeHierarchy()),
    )

    // ------------------------------------------------------------------ translate

    private fun translate() = RpcMethod(
        name = "translate",
        summary = "Translate code between languages (or serialize its Meaning Tree)",
        description = "Parses source code with the --from translator and either generates --to code, or serializes the Meaning Tree.",
        params = listOf(
            ParamDoc("code", "Source code to translate.", true, Schemas.fileSource()),
            ParamDoc("from", "Source language.", true, Schemas.string()),
            ParamDoc("to", "Target language (omit when using 'serialize').", false, Schemas.string()),
            ParamDoc("serialize", "Serialize the Meaning Tree instead of generating code.", false, Schemas.enumOf("json", "xml", "dot", "rdf", "rdf-turtle")),
            ParamDoc("mode", "Translator mode.", false, Schemas.enumOf("expression", "simple", "procedural", "full")),
            ParamDoc("prettify", "Prettify serializer output.", false, Schemas.boolean(false)),
            ParamDoc("tokenize", "Tokenize target code (output is the token list).", false, Schemas.boolean(false)),
            ParamDoc("tokenizeNoConvert", "Tokenize source code without converting to another language.", false, Schemas.boolean(false)),
            ParamDoc("detailedTokens", "Emit detailed tokens.", false, Schemas.boolean(false)),
            ParamDoc("sourceMap", "Output a source map instead of code.", false, Schemas.boolean(false)),
            ParamDoc("saveBytes", "Save byte positions in the Meaning Tree.", false, Schemas.boolean(false)),
            ParamDoc("skipErrors", "Allow the translator/parser to skip recoverable errors.", false, Schemas.boolean(false)),
            ParamDoc("startNodeId", "Start id for the node id counter.", false, Schemas.integer()),
            ParamDoc("startTokenId", "Start id for the token id counter.", false, Schemas.integer()),
            ParamDoc("config", "Translator config (JSON object) applied per source/target translator.", false, Schemas.obj()),
            ParamDoc("project", "Project source context: object {root, currentFile} or '<root><sep><relPath>'.", false, Schemas.obj()),
        ),
        result = ResultDoc("result", "kind (code|serialized|tokens|sourceMap), format and the output string.", Schemas.obj()),
    ) { call ->
        synchronized(mtLock) { runTranslate(call) }
    }

    private fun runTranslate(call: RpcCall): Map<String, Any?> {
        val p = call.params
        Node.setupId(p.longOrNull("startNodeId") ?: 0)

        val from = (p.textOrNull("from") ?: invalidParams("'from' is required")).lowercase()
        val to = p.textOrNull("to")?.lowercase()
        val serializeFormat = p.textOrNull("serialize")
        val tokenizeNoConvert = p.boolOr("tokenizeNoConvert", false)
        val tokenize = p.boolOr("tokenize", false)
        val sourceMap = p.boolOr("sourceMap", false)
        val prettify = p.boolOr("prettify", false)
        val detailed = p.boolOr("detailedTokens", false)

        if (to == null && serializeFormat == null && !tokenizeNoConvert) {
            invalidParams("Either 'to' (target language) or 'serialize' (format) must be specified")
        }
        if (!translators.containsKey(from)) invalidParams("Unsupported source language '$from'. Supported: ${translators.keys}")
        if (to != null && !translators.containsKey(to)) invalidParams("Unsupported target language '$to'. Supported: ${translators.keys}")

        val code = call.workspace.resolveText(p.child("code"), "code")
        val mode = parseMode(p.textOrNull("mode"))

        var fromConfig = Config(
            mode.getConfigEntry(),
            ConfigParameters.bytePositionAnnotations.withValue(p.boolOr("saveBytes", false)),
            ConfigParameters.skipErrors.withValue(p.boolOr("skipErrors", false)),
        )
        var toConfig = fromConfig.clone()
        configObject(p)?.let { element ->
            val fromClass = translators[from]
            val toClass = to?.let { translators[it] }
            val fromJson = JsonObject()
            val toJson = JsonObject()
            for (key in element.keySet()) {
                if (ConfigParameters.exists(fromClass, key)) fromJson.add(key, element.get(key))
                if (toClass != null && ConfigParameters.exists(toClass, key)) toJson.add(key, element.get(key))
            }
            fromConfig = fromConfig.merge(ConfigBuilder().fromJson(fromClass, fromJson).toConfig())
            if (toClass != null) toConfig = toConfig.merge(ConfigBuilder().fromJson(toClass, toJson).toConfig())
        }

        val fromTranslator = newTranslator(from, fromConfig)
        applyProjectContext(call, fromTranslator)

        val meaningTree = fromTranslator.getMeaningTree(code)
        val rootNode = meaningTree.rootNode

        if (serializeFormat != null) {
            return serialized(serializeFormat, serializeOrFail(rootNode, serializeFormat, prettify))
        }
        if (tokenizeNoConvert) {
            Token.setupId(p.longOrNull("startTokenId") ?: 0)
            val tokens = fromTranslator.getCodeAsTokens(meaningTree, true, detailed, false)
            val fmt = serializeFormat ?: "json"
            return tokensResult(fmt, serializeOrFail(tokens, fmt, prettify))
        }

        val toLang = to!!
        val toTranslator = newTranslator(toLang, toConfig)
        if (sourceMap) {
            val srcMap = SourceMapGenerator(toTranslator).process(meaningTree)
            return sourceMapResult(serializeOrFail(srcMap, "json", prettify))
        }
        if (tokenize) {
            Token.setupId(p.longOrNull("startTokenId") ?: 0)
            val tokens = toTranslator.getCodeAsTokens(meaningTree, true, detailed, false)
            return tokensResult("json", serializeOrFail(tokens, "json", prettify))
        }
        return code(toLang, toTranslator.getCode(meaningTree))
    }

    // ------------------------------------------------------------------ generate

    private fun generate() = RpcMethod(
        name = "generate",
        summary = "Generate code (or re-serialize) from a serialized Meaning Tree",
        description = "Deserializes a Meaning Tree / Node from json|xml|rdf|rdf-turtle and generates --to code, re-serializes it, tokenizes, or builds a source map.",
        params = listOf(
            ParamDoc("input", "Serialized Meaning Tree or Node.", true, Schemas.fileSource()),
            ParamDoc("format", "Input serialization format.", false, Schemas.enumOf("json", "xml", "rdf", "rdf-turtle")),
            ParamDoc("inputType", "Type of the serialized object.", false, Schemas.enumOf("meaning-tree", "node")),
            ParamDoc("to", "Target language (omit when only re-serializing).", false, Schemas.string()),
            ParamDoc("serialize", "Re-serialize to this output format instead of generating code.", false, Schemas.enumOf("json", "xml", "dot", "rdf", "rdf-turtle")),
            ParamDoc("mode", "Translator mode.", false, Schemas.enumOf("expression", "simple", "procedural", "full")),
            ParamDoc("prettify", "Prettify serializer output.", false, Schemas.boolean(false)),
            ParamDoc("tokenize", "Tokenize target code.", false, Schemas.boolean(false)),
            ParamDoc("detailedTokens", "Emit detailed tokens.", false, Schemas.boolean(false)),
            ParamDoc("sourceMap", "Output a source map instead of code.", false, Schemas.boolean(false)),
            ParamDoc("skipErrors", "Allow the translator to skip recoverable errors.", false, Schemas.boolean(false)),
            ParamDoc("startTokenId", "Start id for the token id counter.", false, Schemas.integer()),
            ParamDoc("config", "Translator config (JSON object) applied to the target translator.", false, Schemas.obj()),
        ),
        result = ResultDoc("result", "kind, format and the output string.", Schemas.obj()),
    ) { call ->
        synchronized(mtLock) { runGenerate(call) }
    }

    private fun runGenerate(call: RpcCall): Map<String, Any?> {
        val p = call.params
        val to = p.textOrNull("to")?.lowercase()
        val inputFormat = p.textOrNull("format") ?: "json"
        val outputFormat = p.textOrNull("serialize")
        val isNode = p.textOrNull("inputType") == "node"
        val prettify = p.boolOr("prettify", false)
        val sourceMap = p.boolOr("sourceMap", false)
        val tokenize = p.boolOr("tokenize", false)
        val detailed = p.boolOr("detailedTokens", false)

        val serializeOnly = outputFormat != null && !sourceMap && !tokenize
        if (to == null && !serializeOnly) invalidParams("Either 'to' (target language) or 'serialize' (format) must be specified")
        if (to != null && !translators.containsKey(to)) invalidParams("Unsupported target language '$to'. Supported: ${translators.keys}")

        val text = call.workspace.resolveText(p.child("input"), "input")
        val target = deserialize(text, isNode, inputFormat)
            ?: invalidParams("Unknown input serialization format '$inputFormat'. Supported: $deserializeFormats")

        if (serializeOnly) {
            return serialized(outputFormat!!, serializeOrFail(target, outputFormat, prettify))
        }

        val toLang = to!!
        var config = Config(parseMode(p.textOrNull("mode")).getConfigEntry(), ConfigParameters.skipErrors.withValue(p.boolOr("skipErrors", false)))
        configObject(p)?.let { element ->
            config = config.merge(ConfigBuilder().fromJson(translators[toLang], element).toConfig())
        }
        val toTranslator = newTranslator(toLang, config)

        if (isNode) {
            if (sourceMap) {
                val srcMap = SourceMapGenerator(toTranslator).process(target as Node)
                val fmt = outputFormat ?: "json"
                return sourceMapResult(serializeOrFail(srcMap, fmt, prettify))
            }
            return code(toLang, toTranslator.getCode(target as Node))
        }
        val tree = target as MeaningTree
        if (sourceMap) {
            val srcMap = SourceMapGenerator(toTranslator).process(tree)
            val fmt = outputFormat ?: "json"
            return sourceMapResult(serializeOrFail(srcMap, fmt, prettify))
        }
        if (tokenize) {
            Token.setupId(p.longOrNull("startTokenId") ?: 0)
            val tokens = toTranslator.getCodeAsTokens(tree, true, detailed, false)
            val fmt = outputFormat ?: "json"
            return tokensResult(fmt, serializeOrFail(tokens, fmt, prettify))
        }
        return code(toLang, toTranslator.getCode(tree))
    }

    // ------------------------------------------------------------------ list-langs / node-hierarchy

    private fun listLangs() = RpcMethod(
        name = "list-langs",
        summary = "List supported languages",
        description = "Returns all source/target languages supported by meaning_tree.",
        params = emptyList(),
        result = ResultDoc("result", "Supported language identifiers.", Schemas.obj()),
    ) { _ -> mapOf("languages" to translators.keys.sorted()) }

    private fun nodeHierarchy() = RpcMethod(
        name = "node-hierarchy",
        summary = "List supported Meaning Tree nodes and their parents",
        description = "Returns the node type hierarchy as a JSON object.",
        params = listOf(ParamDoc("prettify", "Unused (kept for CLI parity); the hierarchy is returned as structured JSON.", false, Schemas.boolean(false))),
        result = ResultDoc("result", "The node hierarchy.", Schemas.obj()),
    ) { _ ->
        val hierarchy: JsonObject = JsonTypeHierarchyBuilder.generateHierarchyJsonObject()
        mapOf("hierarchy" to Json.parse(gson.toJson(hierarchy)))
    }

    // ------------------------------------------------------------------ serializers / helpers

    private fun serializeOrFail(node: Serializable, format: String, pretty: Boolean): String =
        serialize(node, format, pretty)
            ?: invalidParams("Unknown serialization format '$format'. Supported: $serializeFormats")

    private fun serialize(node: Serializable, format: String, pretty: Boolean): String? = when (format) {
        "json" -> (if (pretty) gsonPretty else gson).toJson(JsonSerializer().serialize(node))
        "xml" -> XMLSerializer(pretty).serialize(node)
        "dot" -> GraphvizDotSerializer().serialize(node)
        "rdf" -> serializeRdf(node, "RDF/XML")
        "rdf-turtle" -> serializeRdf(node, "TTL")
        else -> null
    }

    private fun deserialize(text: String, isNode: Boolean, format: String): Serializable? = when (format) {
        "json" -> {
            val obj = JsonParser.parseString(text).asJsonObject
            if (isNode) JsonDeserializer().deserialize(obj) else JsonDeserializer().deserializeTree(obj)
        }
        "xml" -> if (isNode) XMLDeserializer().deserialize(text) else XMLDeserializer().deserializeTree(text)
        "rdf" -> deserializeRdf(text, isNode, "RDF/XML")
        "rdf-turtle" -> deserializeRdf(text, isNode, "TTL")
        else -> null
    }

    private fun serializeRdf(node: Serializable, format: String): String {
        val model = RDFSerializer().serialize(node)
        return StringWriter().also { model.write(it, format) }.toString()
    }

    private fun deserializeRdf(text: String, isNode: Boolean, format: String): Serializable {
        val model = ModelFactory.createDefaultModel()
        model.read(StringReader(text), null, format)
        return if (isNode) RDFDeserializer().deserialize(model) else RDFDeserializer().deserializeTree(model)
    }

    private fun newTranslator(language: String, config: Config): LanguageTranslator =
        translators.getValue(language).getDeclaredConstructor(Config::class.java).newInstance(config)

    private fun parseMode(value: String?): Main.TranslatorMode {
        if (value == null) return Main.TranslatorMode.full
        return runCatching { Main.TranslatorMode.valueOf(value) }
            .getOrElse { invalidParams("Unknown mode '$value'. Expected: expression, simple, procedural, full") }
    }

    private fun configObject(p: JsonNode?): JsonObject? {
        val node = p.child("config") ?: return null
        return JsonParser.parseString(Json.writeString(node)).asJsonObject
    }

    private fun applyProjectContext(call: RpcCall, translator: LanguageTranslator) {
        val node = call.params.child("project") ?: return
        val (root, currentFile) = when {
            node.isTextual -> {
                val raw = node.asText()
                val idx = raw.indexOf(File.pathSeparator)
                if (idx < 0) invalidParams("project string must be '<root>${File.pathSeparator}<currentFileRelPath>'")
                raw.substring(0, idx).trim() to raw.substring(idx + File.pathSeparator.length).trim()
            }
            node.isObject -> {
                val r = node.textOrNull("root") ?: invalidParams("project.root is required")
                val c = node.textOrNull("currentFile") ?: invalidParams("project.currentFile is required")
                r to c
            }
            else -> invalidParams("project must be a string or an object {root, currentFile}")
        }
        if (root.isEmpty() || currentFile.isEmpty()) invalidParams("project root and currentFile must be non-empty")
        translator.withSourceContext(Path.of(root), Path.of(currentFile))
    }

    private fun serialized(format: String, value: String) = mapOf("kind" to "serialized", "format" to format, "result" to value)
    private fun tokensResult(format: String, value: String) = mapOf("kind" to "tokens", "format" to format, "result" to value)
    private fun sourceMapResult(value: String) = mapOf("kind" to "sourceMap", "format" to "json", "result" to value)
    private fun code(language: String, value: String) = mapOf("kind" to "code", "format" to language, "result" to value)
}
