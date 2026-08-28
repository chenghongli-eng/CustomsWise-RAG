package com.customswise.rag.service;

/**
 * MiniMax API 业务异常基类。
 *
 * <p>区分语义：
 * <ul>
 *   <li>{@link Timeout} —— 网络超时（socket / connect timeout），可降级</li>
 *   <li>{@link Business} —— HTTP 4xx（鉴权、参数错误、配额耗尽），属于业务故障</li>
 *   <li>{@link ServerError} —— HTTP 5xx，服务端故障</li>
 *   <li>{@link Network} —— DNS / 连接被拒 / 其它 IOException</li>
 * </ul>
 *
 * <p>调用方（{@link RAGService}）根据类型决定日志埋点和降级策略：
 * <ul>
 *   <li>Timeout → answer 填降级文案，HTTP 仍 200</li>
 *   <li>Business/Server/Network → answer 填业务错误文案，日志 {@code chat_error=true}，HTTP 200</li>
 * </ul>
 */
public abstract class MiniMaxException extends RuntimeException {

    public MiniMaxException(String message) {
        super(message);
    }

    public MiniMaxException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 连接/读取超时——可降级。 */
    public static class Timeout extends MiniMaxException {
        public Timeout(String message) { super(message); }
        public Timeout(String message, Throwable cause) { super(message, cause); }
    }

    /** HTTP 4xx——业务故障（鉴权、参数、配额）。 */
    public static class Business extends MiniMaxException {
        private final int statusCode;
        public Business(int statusCode, String body) {
            super("MiniMax business error status=" + statusCode + " body=" + truncate(body));
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }

    /** HTTP 5xx——服务端故障。 */
    public static class ServerError extends MiniMaxException {
        private final int statusCode;
        public ServerError(int statusCode, String body) {
            super("MiniMax server error status=" + statusCode + " body=" + truncate(body));
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }

    /** 网络层错误（DNS 失败、连接被拒等）。 */
    public static class Network extends MiniMaxException {
        public Network(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}