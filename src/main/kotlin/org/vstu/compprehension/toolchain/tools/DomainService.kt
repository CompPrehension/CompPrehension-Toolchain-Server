package org.vstu.compprehension.toolchain.tools

import com.fasterxml.jackson.databind.JsonNode
import its.model.DirectoryScanUtils
import its.model.DomainSolvingModel
import its.model.definition.DomainModel
import its.model.definition.compat.DomainDictionariesRDFBuilder
import its.model.definition.loqi.DomainLoqiBuilder
import its.model.definition.loqi.DomainLoqiWriter
import its.model.definition.loqi.LoqiWriteOptions
import its.model.definition.loqi.TreeLoqiBuilder
import its.model.definition.loqi.TreeLoqiWriter
import its.model.definition.rdf.DomainRDFFiller
import its.model.definition.rdf.DomainRDFWriter
import its.model.definition.rdf.RDFUtils
import its.model.nodes.DecisionTree
import its.model.nodes.xml.DecisionTreeXMLBuilder
import its.model.nodes.xml.DecisionTreeXMLWriter
import org.vstu.compprehension.toolchain.boolOr
import org.vstu.compprehension.toolchain.child
import org.vstu.compprehension.toolchain.rpc.ParamDoc
import org.vstu.compprehension.toolchain.rpc.ResultDoc
import org.vstu.compprehension.toolchain.rpc.RpcCall
import org.vstu.compprehension.toolchain.rpc.RpcMethod
import org.vstu.compprehension.toolchain.rpc.Schemas
import org.vstu.compprehension.toolchain.rpc.ToolModule
import org.vstu.compprehension.toolchain.rpc.invalidParams
import org.vstu.compprehension.toolchain.textOrNull
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path

/**
 * Wraps the `its_DomainModel` CLI (`domain-cli`) as JSON-RPC methods. Each method mirrors a CLI
 * subcommand but returns structured JSON instead of printing to stdout.
 */
object DomainService {

    /** Guards the global mutable flag [DecisionTreeXMLWriter.SHOULD_USE_CDATA_EXPRESSIONS]. */
    private val cdataLock = Any()

    fun module(): ToolModule = ToolModule(
        route = "/rpc/domain",
        title = "its_DomainModel",
        description = "Validation and conversion of domain models and decision trees (LOQI / XML / RDF / CSV dictionaries).",
        methods = listOf(
            validateDsm(),
            treeLoqiToXml(),
            decompileTree(),
            dictToLoqi(),
            validateDomainLoqi(),
            domainToRdf(),
            rdfToDomainLoqi(),
        ),
    )

    // ------------------------------------------------------------------ validate-dsm

    private fun validateDsm() = RpcMethod(
        name = "validate-dsm",
        summary = "Validate a DomainSolvingModel directory",
        description = "Builds a DomainSolvingModel from an imitated directory and validates it.",
        params = listOf(
            ParamDoc("model", "Directory with domain.loqi, tag_*.loqi and tree files (or CSV+TTL for DICT_RDF).", true, Schemas.dirSource()),
            ParamDoc("buildMethod", "How to build the model.", false, Schemas.enumOf("LOQI", "DICT_RDF")),
            ParamDoc("debug", "Skip the check for debug-namespace procedures in decision trees.", false, Schemas.boolean(false)),
        ),
        result = ResultDoc("result", "Validation outcome.", Schemas.obj()),
    ) { call ->
        val dir = call.workspace.materializeDir(call.params.child("model"), "model")
        val buildMethod = parseBuildMethod(call.params.textOrNull("buildMethod"))
        val debug = call.params.boolOr("debug", false)
        DomainSolvingModel(dir.toString(), buildMethod).validate(debug = debug)
        mapOf("valid" to true, "buildMethod" to buildMethod.name)
    }

    // ------------------------------------------------------------------ tree-loqi-to-xml

    private fun treeLoqiToXml() = RpcMethod(
        name = "tree-loqi-to-xml",
        summary = "Convert a decision tree from LOQI to XML",
        description = "Parses a LOQI/TPG decision tree and serializes it as XML. Optionally validates it against a model.",
        params = listOf(
            ParamDoc("treeLoqi", "Decision tree in LOQI format.", true, Schemas.fileSource()),
            ParamDoc("modelDir", "If present, validate the tree against this DomainSolvingModel directory.", false, Schemas.dirSource()),
            ParamDoc("tag", "Domain tag used for validation (requires modelDir).", false, Schemas.string()),
            ParamDoc("useCDataExpressions", "Write expressions as CDATA with LOQI representation.", false, Schemas.boolean(false)),
        ),
        result = ResultDoc("result", "The XML string and optional validation info.", Schemas.obj()),
    ) { call ->
        val loqi = call.workspace.resolveText(call.params.child("treeLoqi"), "treeLoqi")
        val decisionTree = StringReader(loqi).use(TreeLoqiBuilder::buildTree)

        val validatedFor = validateTreeIfRequested(call, decisionTree)
        val useCData = call.params.boolOr("useCDataExpressions", false)

        val xml = synchronized(cdataLock) {
            val previous = DecisionTreeXMLWriter.SHOULD_USE_CDATA_EXPRESSIONS
            DecisionTreeXMLWriter.SHOULD_USE_CDATA_EXPRESSIONS = useCData
            try {
                DecisionTreeXMLWriter.decisionTreeToXmlString(decisionTree)
            } finally {
                DecisionTreeXMLWriter.SHOULD_USE_CDATA_EXPRESSIONS = previous
            }
        }
        buildMap<String, Any?> {
            put("xml", xml)
            if (validatedFor != null) put("validatedFor", validatedFor)
        }
    }

    private fun validateTreeIfRequested(call: RpcCall, decisionTree: DecisionTree): Map<String, Any?>? {
        val modelDirNode = call.params.child("modelDir") ?: run {
            if (call.params.textOrNull("tag") != null) invalidParams("'tag' can only be used together with 'modelDir'")
            return null
        }
        val tag = call.params.textOrNull("tag")
        val dir = call.workspace.materializeDir(modelDirNode, "modelDir")
        val model = DomainSolvingModel(dir.toString(), DomainSolvingModel.BuildMethod.LOQI)
        val domain = resolveDomain(model, tag)
        domain.validateAndThrow()
        decisionTree.validate(domain)
        return mapOf("tag" to tag)
    }

    // ------------------------------------------------------------------ decompile-tree

    private fun decompileTree() = RpcMethod(
        name = "decompile-tree",
        summary = "Decompile a decision tree from XML to LOQI/TPG (experimental)",
        description = "Reads an XML decision tree and emits its LOQI/TPG representation. Experimental: not production-ready.",
        params = listOf(
            ParamDoc("treeXml", "Decision tree in XML format.", true, Schemas.fileSource()),
            ParamDoc("treeName", "Tree name used in the TPG header.", false, Schemas.string()),
        ),
        result = ResultDoc("result", "The TPG/LOQI string plus an experimental warning.", Schemas.obj()),
    ) { call ->
        val xmlFile: Path = call.workspace.materializeFile(call.params.child("treeXml"), "treeXml", "tree.xml")
        val treeName = call.params.textOrNull("treeName") ?: "ExprEval"
        val decisionTree = DecisionTreeXMLBuilder.fromXMLFile(xmlFile.toUri().toString())
        val writer = StringWriter()
        TreeLoqiWriter.writeTree(decisionTree, writer, treeName)
        mapOf(
            "tpg" to writer.toString(),
            "warning" to EXPERIMENTAL_DECOMPILE_WARNING,
        )
    }

    // ------------------------------------------------------------------ dict-to-loqi

    private fun dictToLoqi() = RpcMethod(
        name = "dict-to-loqi",
        summary = "Build a domain from CSV dictionaries + domain.ttl and emit LOQI",
        description = "Builds a domain from enums/classes/properties/relationships CSV plus domain.ttl, returns domain.loqi and any decision-tree files found.",
        params = listOf(
            ParamDoc("model", "Directory with enums.csv, classes.csv, properties.csv, relationships.csv and domain.ttl.", true, Schemas.dirSource()),
            ParamDoc("separateMetadata", "Emit metadata in separate LOQI sections.", false, Schemas.boolean(false)),
            ParamDoc("separateClassPropertyValues", "Emit class property values in separate LOQI sections.", false, Schemas.boolean(false)),
        ),
        result = ResultDoc("result", "domain.loqi content and copied tree files.", Schemas.obj()),
    ) { call ->
        val dir = call.workspace.materializeDir(call.params.child("model"), "model")
        val domain = DomainDictionariesRDFBuilder.buildDomain(dir.toString())
        val options = loqiWriteOptions(call)
        val loqi = StringWriter().also { DomainLoqiWriter.saveDomain(domain, it, options) }.toString()
        val trees = collectTreeFiles(dir)
        mapOf("domainLoqi" to loqi, "trees" to trees)
    }

    // ------------------------------------------------------------------ validate-domain-loqi

    private fun validateDomainLoqi() = RpcMethod(
        name = "validate-domain-loqi",
        summary = "Validate an arbitrary domain LOQI merged into a model",
        description = "Merges an extra domain LOQI into a (tag-resolved) DomainSolvingModel domain and validates the result.",
        params = listOf(
            ParamDoc("domainLoqi", "Arbitrary domain LOQI file.", true, Schemas.fileSource()),
            ParamDoc("model", "DomainSolvingModel directory.", true, Schemas.dirSource()),
            ParamDoc("tag", "Tag from the model to merge.", false, Schemas.string()),
            ParamDoc("printMergedLoqi", "Include the merged model as LOQI in the response.", false, Schemas.boolean(false)),
        ),
        result = ResultDoc("result", "Validation outcome and optional merged LOQI.", Schemas.obj()),
    ) { call ->
        val dir = call.workspace.materializeDir(call.params.child("model"), "model")
        val extraLoqi = call.workspace.resolveText(call.params.child("domainLoqi"), "domainLoqi")
        val tag = call.params.textOrNull("tag")
        val model = DomainSolvingModel(dir.toString(), DomainSolvingModel.BuildMethod.LOQI)
        val extraDomain = StringReader(extraLoqi).use(DomainLoqiBuilder::buildDomain)
        val merged = resolveDomain(model, tag).copy().apply { addMerge(extraDomain) }
        merged.validateAndThrow()
        buildMap<String, Any?> {
            put("valid", true)
            put("tag", tag)
            if (call.params.boolOr("printMergedLoqi", false)) {
                put("mergedLoqi", StringWriter().also { DomainLoqiWriter.saveDomain(merged, it) }.toString())
            }
        }
    }

    // ------------------------------------------------------------------ domain-to-rdf

    private fun domainToRdf() = RpcMethod(
        name = "domain-to-rdf",
        summary = "Build a concrete domain and write it as RDF Turtle",
        description = "Resolves a concrete domain from a model (optionally merging an extra LOQI) and serializes it to TTL.",
        params = listOf(
            ParamDoc("model", "DomainSolvingModel directory.", true, Schemas.dirSource()),
            ParamDoc("buildMethod", "How to build the model.", false, Schemas.enumOf("LOQI", "DICT_RDF")),
            ParamDoc("tag", "Tag from the model to merge.", false, Schemas.string()),
            ParamDoc("domainLoqi", "Extra domain LOQI to merge before export.", false, Schemas.fileSource()),
            ParamDoc("basePrefix", "Base RDF prefix for created resources.", false, Schemas.string()),
            ParamDoc("oldNaryCompat", "Use the legacy n-ary relationship representation.", false, Schemas.boolean(false)),
        ),
        result = ResultDoc("result", "The TTL string.", Schemas.obj()),
    ) { call ->
        val domain = resolveConcreteDomain(call)
        domain.validateAndThrow()
        val basePrefix = call.params.textOrNull("basePrefix") ?: RDFUtils.POAS_PREF
        val options = buildSet {
            if (call.params.boolOr("oldNaryCompat", false)) add(DomainRDFWriter.Option.NARY_RELATIONSHIPS_OLD_COMPAT)
        }
        val ttl = StringWriter().also { DomainRDFWriter.saveDomain(domain, it, basePrefix, options) }.toString()
        mapOf("ttl" to ttl, "basePrefix" to basePrefix)
    }

    // ------------------------------------------------------------------ rdf-to-domain-loqi

    private fun rdfToDomainLoqi() = RpcMethod(
        name = "rdf-to-domain-loqi",
        summary = "Fill a concrete domain from RDF TTL and emit LOQI",
        description = "Fills a concrete domain (from a model, optionally merged with extra LOQI) using RDF Turtle data, then serializes it to LOQI.",
        params = listOf(
            ParamDoc("model", "DomainSolvingModel directory.", true, Schemas.dirSource()),
            ParamDoc("rdfTtl", "RDF Turtle file with the data to load.", true, Schemas.fileSource()),
            ParamDoc("buildMethod", "How to build the model.", false, Schemas.enumOf("LOQI", "DICT_RDF")),
            ParamDoc("tag", "Tag from the model to merge.", false, Schemas.string()),
            ParamDoc("domainLoqi", "Extra domain LOQI to merge before filling.", false, Schemas.fileSource()),
            ParamDoc("basePrefix", "Base RDF prefix (taken from the TTL or default if omitted).", false, Schemas.string()),
            ParamDoc("oldNaryCompat", "Use the legacy n-ary relationship representation.", false, Schemas.boolean(false)),
            ParamDoc("throwInvalidMeta", "Fail when presumed metadata in RDF are not literal values.", false, Schemas.boolean(false)),
            ParamDoc("separateMetadata", "Emit metadata in separate LOQI sections.", false, Schemas.boolean(false)),
            ParamDoc("separateClassPropertyValues", "Emit class property values in separate LOQI sections.", false, Schemas.boolean(false)),
        ),
        result = ResultDoc("result", "The LOQI string.", Schemas.obj()),
    ) { call ->
        val domain = resolveConcreteDomain(call)
        val ttlFile = call.workspace.materializeFile(call.params.child("rdfTtl"), "rdfTtl", "data.ttl")
        val fillOptions = buildSet {
            if (call.params.boolOr("oldNaryCompat", false)) add(DomainRDFFiller.Option.NARY_RELATIONSHIPS_OLD_COMPAT)
            if (call.params.boolOr("throwInvalidMeta", false)) add(DomainRDFFiller.Option.THROW_INVALID_META)
        }
        DomainRDFFiller.fillDomain(domain, ttlFile.toString(), fillOptions, call.params.textOrNull("basePrefix"))
        val loqi = StringWriter().also { DomainLoqiWriter.saveDomain(domain, it, loqiWriteOptions(call)) }.toString()
        mapOf("loqi" to loqi)
    }

    // ------------------------------------------------------------------ shared helpers

    private fun resolveConcreteDomain(call: RpcCall): DomainModel {
        val dir = call.workspace.materializeDir(call.params.child("model"), "model")
        val buildMethod = parseBuildMethod(call.params.textOrNull("buildMethod"))
        val tag = call.params.textOrNull("tag")
        val model = DomainSolvingModel(dir.toString(), buildMethod)
        val domain = resolveDomain(model, tag).copy()
        call.params.child("domainLoqi")?.let { node ->
            val extraLoqi = call.workspace.resolveText(node, "domainLoqi")
            domain.addMerge(StringReader(extraLoqi).use(DomainLoqiBuilder::buildDomain))
        }
        return domain
    }

    private fun resolveDomain(model: DomainSolvingModel, tag: String?): DomainModel {
        if (tag == null) return model.domainModel
        if (!model.tagsData.containsKey(tag)) {
            val known = model.tagsData.keys.sorted().ifEmpty { listOf("<none>") }.joinToString(", ")
            invalidParams("Tag '$tag' not found. Known tags: $known")
        }
        return model.getMergedTagDomain(tag)
    }

    private fun parseBuildMethod(value: String?): DomainSolvingModel.BuildMethod {
        if (value == null) return DomainSolvingModel.BuildMethod.LOQI
        return runCatching { DomainSolvingModel.BuildMethod.valueOf(value) }
            .getOrElse { invalidParams("Unknown buildMethod '$value'. Expected: LOQI or DICT_RDF") }
    }

    private fun loqiWriteOptions(call: RpcCall): Set<LoqiWriteOptions> = buildSet {
        if (call.params.boolOr("separateMetadata", false)) add(LoqiWriteOptions.SEPARATE_METADATA)
        if (call.params.boolOr("separateClassPropertyValues", false)) add(LoqiWriteOptions.SEPARATE_CLASS_PROPERTY_VALUES)
    }

    /** Reads the same tree/tpg files the CLI's dict-to-loqi would copy, returning name -> content. */
    private fun collectTreeFiles(dir: Path): Map<String, String> {
        val url = dir.toUri().toURL()
        val matches = buildList {
            addAll(DirectoryScanUtils.findFilesMatching(url, Regex("((?:tree|tpg)_\\S+|tree)\\.xml")))
            addAll(DirectoryScanUtils.findFilesMatching(url, Regex("((?:tree|tpg)_\\S+|tree)\\.loqi")))
            addAll(DirectoryScanUtils.findFilesMatching(url, Regex("(\\S+)\\.tpg")))
        }
        return matches.associate { match ->
            val path = Path.of(match.url.toURI())
            path.fileName.toString() to path.toFile().readText()
        }
    }

    private const val EXPERIMENTAL_DECOMPILE_WARNING =
        "decompile-tree is experimental and does not generate production-ready thought process graphs; use it primarily for analysis."
}
