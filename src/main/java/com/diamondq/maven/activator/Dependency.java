package com.diamondq.maven.activator;

import com.google.common.base.Objects;

import java.io.File;

import org.apache.maven.model.profile.ProfileActivationContext;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.StringUtils;

public abstract class Dependency {

  public abstract boolean isValid(ProfileActivationContext pContext, Logger logger);

  public static Dependency onFile(File pFile) {
    boolean originalExists = pFile.exists();
    return new Dependency() {

      @Override
      public boolean isValid(ProfileActivationContext pContext, Logger logger) {
        return pFile.exists() == originalExists;
      }
    };
  }

  public static Dependency onNoStartsWith(File pProfilesDir, String pPrefix) {
    return new Dependency() {

      @Override
      public boolean isValid(ProfileActivationContext pContext, Logger logger) {
        for (File file : pProfilesDir.listFiles()) {
          if (file.getName().startsWith(pPrefix) == true)
            return false;
        }
        return true;
      }
    };
  }

  public static Dependency onFail() {
    return new Dependency() {

      @Override
      public boolean isValid(ProfileActivationContext pContext, Logger logger) {
        return false;
      }
    };
  }

  public static Dependency onProperty(boolean pUserProperty, String pPropKey, String pPropValue, boolean pReverse) {
    return new Dependency() {

      @Override
      public boolean isValid(ProfileActivationContext pContext, Logger pLogger) {
        String sysValue;
        if (pUserProperty == true)
          sysValue = pContext.getUserProperties().get(pPropKey);
        else
          sysValue = pContext.getSystemProperties().get(pPropKey);
        if (StringUtils.isNotEmpty(sysValue)) {
          if (pPropValue == null) {
            if (pReverse == true) {
              if (pLogger != null)
                pLogger.debug("[DIAMONDQ Profile Activator] property \"" + pPropKey + "\" exists so false");
              return false;
            }
            if (pLogger != null)
              pLogger.debug("[DIAMONDQ Profile Activator] property \"" + pPropKey + "\" exists so true");
            return true;
          }
          else {
            if (pPropValue.equals(sysValue)) {
              if (pReverse == true) {
                if (pLogger != null)
                  pLogger.debug("[DIAMONDQ Profile Activator] property \"" + pPropKey + "\" -> \"" + sysValue
                    + "\" equals to \"" + pPropValue + "\" so false");
                return false;
              }
              if (pLogger != null)
                pLogger.debug("[DIAMONDQ Profile Activator] property \"" + pPropKey + "\" -> \"" + sysValue
                  + "\" equals to \"" + pPropValue + "\" so true");
              return true;
            }
            else {
              if (pReverse == true) {
                if (pLogger != null)
                  pLogger.debug("[DIAMONDQ Profile Activator] property \"" + pPropKey + "\" -> \"" + sysValue
                    + "\" not equals to \"" + pPropValue + "\" so true");
                return true;
              }
              if (pLogger != null)
                pLogger.debug("[DIAMONDQ Profile Activator] property \"" + pPropKey + "\" -> \"" + sysValue
                  + "\" not equals to \"" + pPropValue + "\" so false");
              return false;
            }
          }
        }
        else {
          if (pReverse == true) {
            if (pLogger != null)
              pLogger.debug("[DIAMONDQ Profile Activator] property \"" + pPropKey + "\" does not exist so true");
            return true;
          }
          if (pLogger != null)
            pLogger.debug("[DIAMONDQ Profile Activator] property \"" + pPropKey + "\" does not exist so false");
          return false;
        }
      }
    };
  }

  public static Dependency onProjectDir(File pBasedir) {
    String matchingDir = pBasedir == null ? null : pBasedir.toString();
    return new Dependency() {

      @Override
      public boolean isValid(ProfileActivationContext pContext, Logger pLogger) {
        File baseDir = pContext.getProjectDirectory();
        String testDir = baseDir == null ? null : baseDir.toString();
        return Objects.equal(matchingDir, testDir);
      }
    };
  }

}
