package com.luanshuai.agent.langchain4j.exception;

/**
 * Tool 执行异常
 * 当 LangChain4j Agent 执行 Tool 时发生错误抛出此异常
 */
public class ToolExecutionException extends RuntimeException {

    private final String toolName;
    private final String arguments;

    public ToolExecutionException(String message) {
        super(message);
        this.toolName = null;
        this.arguments = null;
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
        this.toolName = null;
        this.arguments = null;
    }

    public ToolExecutionException(String toolName, String arguments, String message) {
        super(message);
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public ToolExecutionException(String toolName, String arguments, String message, Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        if (toolName != null) {
            return String.format("ToolExecutionException[tool=%s, args=%s]: %s",
                    toolName, arguments, getMessage());
        }
        return super.toString();
    }
}