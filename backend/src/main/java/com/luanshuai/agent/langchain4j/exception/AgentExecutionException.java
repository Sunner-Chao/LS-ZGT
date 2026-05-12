package com.luanshuai.agent.langchain4j.exception;

/**
 * Agent 执行异常
 * 当 LangChain4j Agent 运行时发生错误抛出此异常
 */
public class AgentExecutionException extends RuntimeException {

    private final int iteration;
    private final String lastAction;

    public AgentExecutionException(String message) {
        super(message);
        this.iteration = -1;
        this.lastAction = null;
    }

    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
        this.iteration = -1;
        this.lastAction = null;
    }

    public AgentExecutionException(String message, int iteration, String lastAction) {
        super(message);
        this.iteration = iteration;
        this.lastAction = lastAction;
    }

    public AgentExecutionException(String message, Throwable cause, int iteration, String lastAction) {
        super(message, cause);
        this.iteration = iteration;
        this.lastAction = lastAction;
    }

    public int getIteration() {
        return iteration;
    }

    public String getLastAction() {
        return lastAction;
    }

    @Override
    public String toString() {
        if (iteration >= 0) {
            return String.format("AgentExecutionException[iteration=%d, lastAction=%s]: %s",
                    iteration, lastAction, getMessage());
        }
        return super.toString();
    }
}