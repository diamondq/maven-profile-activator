package com.diamondq.maven.activator;

public interface ActivatorLogger {
     void debug(String pMsg);
     void error(String pMsg, Throwable pThrowable);
     void info(String pMsg);
     void warn(String pMsg);

    boolean isDebugEnabled();
}
