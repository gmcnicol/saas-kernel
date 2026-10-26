package io.github.gmcnicol.kernel.internal;

/** THROWAWAY: run the same finite differential tracers on Kit's Noble glibc floor. */
public final class NativeRuntimeSmoke {
    public static void main(String[] args) throws Exception {
        var experiment = new PreparedReadEvaluatorExperiment();
        experiment.decisionsAndDiagnosticsMatchSdkWithDecisionLocalSameUidGraphs();
        experiment.batchDoesNotUnionSameUidGraphsAndStopsBeforeFieldsOfDeniedRecord();
        experiment.requestValidationAndMalformedEntitiesRetainSdkFailure();
        experiment.schemaWarningsAndEvaluationErrorsStopTheBatchBeforeLaterDecisions();
        experiment.malformedTruncatedAndReorderedNativeResultsFailClosed();
        System.out.println("THROWAWAY Noble glibc differential smoke passed");
    }
}
