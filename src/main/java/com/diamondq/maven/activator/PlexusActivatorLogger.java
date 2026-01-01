package com.diamondq.maven.activator;

import org.codehaus.plexus.logging.Logger;

public class PlexusActivatorLogger implements ActivatorLogger {
    private final Logger mLogger;

    public PlexusActivatorLogger(Logger pLogger) {
        mLogger = pLogger;
    }


    @Override
    public void debug(String pMsg) {
        mLogger.debug(pMsg);
    }

    @Override
    public void error(String pMsg, Throwable pThrowable) {
        mLogger.error(pMsg, pThrowable);
    }

    @Override
    public void info(String pMsg) {
        mLogger.info(pMsg);
    }

    @Override
    public void warn(String pMsg) {
        mLogger.warn(pMsg);
    }

    @Override
    public boolean isDebugEnabled() {
        return mLogger.isDebugEnabled();
    }
}
