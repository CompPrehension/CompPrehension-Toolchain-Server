package org.vstu.compprehension.toolchain.tools

import its.model.DomainSolvingModel
import its.model.definition.DomainModel
import its.model.definition.loqi.DomainLoqiBuilder
import its.model.definition.loqi.DomainLoqiWriter
import its.model.definition.loqi.OperatorLoqiBuilder
import its.model.nodes.DecisionTree
import its.reasoner.LearningSituation
import its.reasoner.ReasoningControl
import its.reasoner.ReasoningException
import its.reasoner.ReasoningOptions
import its.reasoner.nodes.DecisionTreeReasoner.Companion.solve
import its.reasoner.nodes.DecisionTreeTrace
import its.reasoner.operators.ExpressionQueryManager
import its.reasoner.utils.branchResultExceptionsEvent
import its.reasoner.utils.expressionTraceEvent
import its.reasoner.utils.formatDecisionTreeTrace
import its.reasoner.utils.formatExpressionTraces
import its.reasoner.utils.formatPartialDecisionTreeTrace
import its.reasoner.utils.partialExpressionTraceEvent
import its.reasoner.utils.partialTraceEvent
import its.reasoner.utils.traceEvent
import its.reasoner.utils.variablesEvent
import org.vstu.compprehension.toolchain.boolOr
import org.vstu.compprehension.toolchain.child
import org.vstu.compprehension.toolchain.longOrNull
import org.vstu.compprehension.toolchain.intOrNull
import org.vstu.compprehension.toolchain.rpc.ParamDoc
import org.vstu.compprehension.toolchain.rpc.ResultDoc
import org.vstu.compprehension.toolchain.rpc.RpcCall
import org.vstu.compprehension.toolchain.rpc.RpcErrorCodes
import org.vstu.compprehension.toolchain.rpc.RpcException
import org.vstu.compprehension.toolchain.rpc.RpcMethod
import org.vstu.compprehension.toolchain.rpc.Schemas
import org.vstu.compprehension.toolchain.rpc.ToolModule
import org.vstu.compprehension.toolchain.rpc.invalidParams
import org.vstu.compprehension.toolchain.textOrNull
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import kotlin.system.measureNanoTime

/**
 * Wraps the `its_Reasoner` CLI (`reasoner-cli`) as JSON-RPC methods. The Reasoner already provides
 * structured JSON event builders (see its.reasoner.utils.CliJsonEvents), which are reused here.
 */
object ReasonerService {

    fun module(): ToolModule = ToolModule(
        route = "/rpc/reasoner",
        title = "its_Reasoner",
        description = "Runs decision-tree reasoning and LOQI expression queries over a domain model.",
        methods = listOf(reason(), expressionQuery()),
    )

    // ------------------------------------------------------------------ reason

    private fun reason() = RpcMethod(
        name = "reason",
        summary = "Run decision-tree reasoning on a situation",
        description = "Merges a base model with a specific LOQI domain, runs reasoning and returns the result, variables, exceptions and trace.",
        params = listOf(
            ParamDoc("model", "DomainSolvingModel directory.", true, Schemas.dirSource()),
            ParamDoc("domainLoqi", "Specific situation domain in LOQI format.", true, Schemas.fileSource()),
            ParamDoc("tag", "Base model tag to merge with the specific domain.", false, Schemas.string()),
            ParamDoc("tree", "Decision tree name (default: the unnamed tree).", false, Schemas.string()),
            ParamDoc("verbose", "Include verbose trace details.", false, Schemas.boolean(false)),
            ParamDoc("debug", "Include debug metadata and collect partial traces.", false, Schemas.boolean(false)),
            ParamDoc("jsonTrace", "Return the trace as structured JSON (true) or a formatted string (false).", false, Schemas.boolean(true)),
            ParamDoc("timeLimitSeconds", "Stop reasoning after the given number of seconds.", false, Schemas.integer()),
            ParamDoc("timeMeasure", "Include preparation/solve timing metrics.", false, Schemas.boolean(false)),
            ParamDoc("exportDomain", "Include the resulting specific domain (after reasoning) as LOQI.", false, Schemas.boolean(false)),
        ),
        result = ResultDoc("result", "branchResult, variables, exceptions, trace and optional metrics/artifact.", Schemas.obj()),
    ) { call ->
        val params = call.params
        val verbose = params.boolOr("verbose", false)
        val debug = params.boolOr("debug", false)
        val jsonTrace = params.boolOr("jsonTrace", true)
        val collect = debug || verbose

        val dir = call.workspace.materializeDir(params.child("model"), "model")
        val specificLoqi = call.workspace.resolveText(params.child("domainLoqi"), "domainLoqi")
        val tag = params.textOrNull("tag")
        val treeName = params.textOrNull("tree") ?: ""
        val control = parseControl(params.longOrNull("timeLimitSeconds"))

        lateinit var baseDomain: DomainModel
        lateinit var situation: LearningSituation
        lateinit var decisionTree: DecisionTree
        lateinit var trace: DecisionTreeTrace

        val preparationNanos = measureNanoTime {
            val model = DomainSolvingModel(dir.toString(), DomainSolvingModel.BuildMethod.LOQI, includeDebugMeta = debug)
            baseDomain = resolveBaseDomain(model, tag)
            val specificDomain = StringReader(specificLoqi).use(DomainLoqiBuilder::buildDomain)
            val situationDomain = baseDomain.copy().apply {
                addMerge(specificDomain)
                validateAndThrow()
            }
            decisionTree = if (treeName.isEmpty()) model.decisionTree else model.decisionTree(treeName)
            situation = LearningSituation(situationDomain, solvingContext = model)
        }
        val solveNanos = try {
            measureNanoTime {
                trace = decisionTree.solve(situation, ReasoningOptions(control, collect, collect))
            }
        } catch (e: RuntimeException) {
            val rootEx = rootCause(e)
            val re = e.findReasoningCause()
            val data = linkedMapOf<String, Any?>(
                "exceptionName" to e.javaClass.name,
                "rootCause" to rootEx.javaClass.name,
                "rootCauseMessage" to rootEx.message,
                "stackTrace" to shortStackTrace(e),
                "variables" to variablesEvent(situation.decisionTreeVariables.toMap())["value"],
            )
            if (debug) {
                re?.expressionTrace?.let { exTrace ->
                    data["partialExpressionTrace"] = if (jsonTrace)
                        partialExpressionTraceEvent(exTrace, verbose)["value"]
                    else formatExpressionTraces(exTrace, verbose)
                }
                re?.partialDecisionTreeTrace?.let { pt ->
                    data["partialTrace"] = if (jsonTrace)
                        partialTraceEvent(pt, verbose)["value"]
                    else formatPartialDecisionTreeTrace(pt, verbose)
                }
            }
            throw RpcException(RpcErrorCodes.TOOL_EXECUTION_ERROR, e.message ?: e.javaClass.name, data)
        }

        val exceptions = branchResultExceptionsEvent(trace)
        val result = linkedMapOf<String, Any?>(
            "branchResult" to trace.branchResult.toString(),
            "variables" to variablesEvent(trace)["value"],
            "exceptions" to mapOf("found" to exceptions["found"], "items" to exceptions["value"]),
            "trace" to if (jsonTrace) traceEvent(trace, verbose)["value"] else formatDecisionTreeTrace(trace, verbose),
        )
        if (params.boolOr("timeMeasure", false)) {
            result["metrics"] = mapOf(
                "preparation" to durationOf(preparationNanos),
                "solve" to durationOf(solveNanos),
            )
        }
        if (params.boolOr("exportDomain", false)) {
            result["specificDomain"] = mapOf(
                "format" to "loqi",
                "value" to writeSpecificDomain(situation.domainModel, baseDomain),
            )
        }
        result
    }

    // ------------------------------------------------------------------ expression-query

    private fun expressionQuery() = RpcMethod(
        name = "expression-query",
        summary = "Run a LOQI expression query on a domain",
        description = "Evaluates a LOQI expression against a domain (optionally merged with a base model) and returns matching object names.",
        params = listOf(
            ParamDoc("domainLoqi", "Domain in LOQI format.", true, Schemas.fileSource()),
            ParamDoc("query", "LOQI expression to evaluate.", true, Schemas.string()),
            ParamDoc("model", "Optional DomainSolvingModel directory to merge with the domain.", false, Schemas.dirSource()),
            ParamDoc("tag", "Base model tag to merge (requires model).", false, Schemas.string()),
            ParamDoc("debug", "Include debug metadata when building the model.", false, Schemas.boolean(false)),
            ParamDoc("trace", "Collect and return the expression trace.", false, Schemas.boolean(false)),
            ParamDoc("verbose", "Verbose trace formatting.", false, Schemas.boolean(false)),
            ParamDoc("limit", "Maximum number of found objects to return.", false, Schemas.integer()),
            ParamDoc("jsonTrace", "Return the trace as structured JSON (true) or a formatted string (false).", false, Schemas.boolean(true)),
            ParamDoc("timeLimitSeconds", "Stop query execution after the given number of seconds.", false, Schemas.integer()),
            ParamDoc("timeMeasure", "Include query timing metric.", false, Schemas.boolean(false)),
        ),
        result = ResultDoc("result", "objects, optional trace and optional metric.", Schemas.obj()),
    ) { call ->
        val params = call.params
        val limit = params.intOrNull("limit")?.also { if (it < 0) invalidParams("limit must be non-negative") }
        val collectTrace = params.boolOr("trace", false)
        val verbose = params.boolOr("verbose", false)
        val jsonTrace = params.boolOr("jsonTrace", true)
        val control = parseControl(params.longOrNull("timeLimitSeconds"))

        val specificLoqi = call.workspace.resolveText(params.child("domainLoqi"), "domainLoqi")
        val queryText = params.textOrNull("query") ?: invalidParams("'query' is required")
        val tag = params.textOrNull("tag")

        val debug = params.boolOr("debug", false)
        val situation = buildQuerySituation(call, specificLoqi, tag, debug)
        val expression = OperatorLoqiBuilder.buildExp(queryText)

        lateinit var queryResult: its.reasoner.operators.ExpressionQueryResult
        val queryNanos = try {
            measureNanoTime {
                queryResult = ExpressionQueryManager(situation, control).query(expression, collectTrace, limit)
            }
        } catch (e: RuntimeException) {
            val rootEx = rootCause(e)
            val data = linkedMapOf<String, Any?>(
                "exceptionName" to e.javaClass.name,
                "rootCause" to rootEx.javaClass.name,
                "rootCauseMessage" to rootEx.message,
                "stackTrace" to shortStackTrace(e),
            )
            if (debug && collectTrace) {
                e.findReasoningCause()?.expressionTrace?.let { exTrace ->
                    data["partialExpressionTrace"] = if (jsonTrace)
                        partialExpressionTraceEvent(exTrace, verbose)["value"]
                    else formatExpressionTraces(exTrace, verbose)
                }
            }
            throw RpcException(RpcErrorCodes.TOOL_EXECUTION_ERROR, e.message ?: e.javaClass.name, data)
        }

        val result = linkedMapOf<String, Any?>("objects" to queryResult.objectRefs.map { it.objectName })
        if (collectTrace) {
            result["trace"] = if (jsonTrace) expressionTraceEvent(queryResult.trace, verbose)["value"]
            else formatExpressionTraces(queryResult.trace, verbose)
        }
        if (params.boolOr("timeMeasure", false)) {
            result["metrics"] = mapOf("query" to durationOf(queryNanos))
        }
        result
    }

    // ------------------------------------------------------------------ shared helpers

    private fun buildQuerySituation(call: RpcCall, specificLoqi: String, tag: String?, debug: Boolean): LearningSituation {
        val specificDomain = StringReader(specificLoqi).use(DomainLoqiBuilder::buildDomain)
        val modelNode = call.params.child("model")
        if (modelNode == null) {
            if (tag != null) invalidParams("'tag' can be used only when 'model' is specified")
            specificDomain.validateAndThrow()
            return LearningSituation(specificDomain)
        }
        val dir = call.workspace.materializeDir(modelNode, "model")
        val model = DomainSolvingModel(dir.toString(), DomainSolvingModel.BuildMethod.LOQI, includeDebugMeta = debug)
        val situationDomain = resolveBaseDomain(model, tag).copy().apply {
            addMerge(specificDomain)
            validateAndThrow()
        }
        return LearningSituation(situationDomain, solvingContext = model)
    }

    private fun resolveBaseDomain(model: DomainSolvingModel, tag: String?): DomainModel {
        if (tag == null) return model.domainModel
        if (!model.tagsData.containsKey(tag)) {
            val known = model.tagsData.keys.sorted().ifEmpty { listOf("<none>") }.joinToString(", ")
            invalidParams("Tag '$tag' not found. Known tags: $known")
        }
        return model.getMergedTagDomain(tag)
    }

    private fun parseControl(timeLimitSeconds: Long?): ReasoningControl {
        if (timeLimitSeconds == null) return ReasoningControl.NONE
        if (timeLimitSeconds <= 0) invalidParams("timeLimitSeconds must be positive")
        return ReasoningControl.withTimeLimitSeconds(timeLimitSeconds)
    }

    private fun writeSpecificDomain(domainModel: DomainModel, baseDomain: DomainModel): String {
        val specific = domainModel.copy().apply { subtract(baseDomain) }
        return StringWriter().also { DomainLoqiWriter.saveDomain(specific, it) }.toString()
    }

    private fun durationOf(nanos: Long): Map<String, Any> = mapOf(
        "seconds" to nanos / 1_000_000_000.0,
        "milliseconds" to nanos / 1_000_000.0,
    )

    private fun rootCause(e: Throwable): Throwable {
        var current = e
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun shortStackTrace(e: Throwable): String {
        val writer = StringWriter()
        e.printStackTrace(PrintWriter(writer))
        return writer.toString().lineSequence().take(25).joinToString("\n")
    }

    private fun Throwable.findReasoningCause(): ReasoningException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is ReasoningException) return current
            current = current.cause
        }
        return null
    }
}
