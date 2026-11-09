package com.gitory.backend.agent.port;

import java.util.List;

public record AssistantStepReply(Action action, AssistantTool tool, Find find, String postingRef, String repoRef,
                                 String jobRole, List<Question> questions, String say, String askKind,
                                 List<String> options) {

    public enum Action {
        CALL_TOOL,
        ASK_USER,
        FINISH
    }

    public record Find(String company, List<String> keywords) {
    }

    public record Question(String text, Integer charLimit) {
    }

    public static AssistantStepReply call(AssistantTool tool, Find find, String postingRef, String repoRef,
                                          String jobRole, List<Question> questions) {

        return new AssistantStepReply(Action.CALL_TOOL, tool, find, postingRef, repoRef, jobRole, questions, null,
                null, List.of());

    }

    public static AssistantStepReply ask(String say, String askKind, List<String> options) {

        return new AssistantStepReply(Action.ASK_USER, null, null, null, null, null, List.of(), say, askKind,
                options);

    }

    public static AssistantStepReply finish(String say) {

        return new AssistantStepReply(Action.FINISH, null, null, null, null, null, List.of(), say, null, List.of());

    }
}
