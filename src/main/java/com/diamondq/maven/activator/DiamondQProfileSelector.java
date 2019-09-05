package com.diamondq.maven.activator;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.model.building.ModelProblem.Version;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.path.PathTranslator;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.model.profile.activation.ProfileActivator;
import org.apache.maven.model.profile.activation.PropertyProfileActivator;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.interpolation.AbstractValueSource;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.RegexBasedInterpolator;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.StringUtils;

/**
 * Profile selector which combines profiles activated by custom and default activators. Overrides "default" provider.
 */
@Component(role = ProfileSelector.class, hint = "default")
public class DiamondQProfileSelector extends DefaultProfileSelector {

  @Requirement
  protected Logger                 logger;

  @Requirement(role = ProfileActivator.class)
  protected List<ProfileActivator> activatorList = new ArrayList<>();

  @Requirement
  private PathTranslator           pathTranslator;

  /**
   * Profiles activated by both custom and default activators.
   */
  @Override
  public List<Profile> getActiveProfiles(Collection<Profile> profiles, ProfileActivationContext context,
    ModelProblemCollector problems) {
    /*
     * In order to allow the existing profile detection to figure out if this extension is present, we'll inject a
     * custom property
     */

    ProfileActivationContext updatedContext = new ProfileActivationContext() {

      @Override
      public Map<String, String> getUserProperties() {
        return context.getUserProperties();
      }

      @Override
      public Map<String, String> getSystemProperties() {
        Map<String, String> systemProperties = new HashMap<>(context.getSystemProperties());
        systemProperties.put("[DIAMONDQ-PROFILE-ACTIVATOR]", "true");
        return Collections.unmodifiableMap(systemProperties);
      }

      @Override
      public Map<String, String> getProjectProperties() {
        return context.getProjectProperties();
      }

      @Override
      public File getProjectDirectory() {
        return context.getProjectDirectory();
      }

      @Override
      public List<String> getInactiveProfileIds() {
        return context.getInactiveProfileIds();
      }

      @Override
      public List<String> getActiveProfileIds() {
        return context.getActiveProfileIds();
      }
    };

    List<Profile> activeProfileList = new ArrayList<>();
    for (Profile profile : profiles) {
      if (hasActive(profile, updatedContext, problems)) {
        activeProfileList.add(profile);
      }
    }
    activeProfileList.addAll(super.getActiveProfiles(profiles, updatedContext, problems));
    if ((logger.isDebugEnabled() == true) && (activeProfileList.isEmpty() == false))
      logger.info("[DIAMONDQ Profile Activator] SELECT: " + Arrays.toString(activeProfileList.toArray()) + " from "
        + Arrays.toString(profiles.toArray()));
    return activeProfileList;
  }

  protected boolean hasActive(Profile profile, ProfileActivationContext context, ModelProblemCollector problems) {
    for (ProfileActivator activator : activatorList) {
      if (activator instanceof PropertyProfileActivator) {
        if (activator.presentInConfig(profile, context, problems)) {

          Activation activation = profile.getActivation();

          if (activation == null)
            return false;

          ActivationProperty property = activation.getProperty();

          if (property == null)
            return false;

          String name = property.getName();
          if (name.equals("[DIAMONDQ]") == false)
            return false;

          String script = property.getValue();

          logger.debug("[DIAMONDQ Profile Activator] Resolving " + script);

          return recursiveProcess(profile, context, problems, property.getLocation(""), script);
        }
      }
    }
    return false;
  }

  private boolean recursiveProcess(Profile pProfile, ProfileActivationContext pContext, ModelProblemCollector pProblems,
    InputLocation pPropertyLocation, String pScript) {
    logger.debug("[DIAMONDQ Profile Activator] recursiveProcess(" + pScript + ")");

    /* Look for the first opening bracket */

    int offset = pScript.indexOf('(');
    if (offset == -1) {
      pProblems.add(new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
        .setMessage("Unable to parse script when activating the profile " + pProfile.getId())
        .setLocation(pPropertyLocation));
      return false;
    }

    /* Find the matching end bracket */

    int endOffset = pScript.lastIndexOf(')');
    if (endOffset == -1) {
      pProblems.add(new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
        .setMessage("Unable to parse script when activating the profile " + pProfile.getId())
        .setLocation(pPropertyLocation));
      return false;
    }

    /* Get the keyword and args */

    String keyword = pScript.substring(0, offset).trim();
    String args = pScript.substring(offset + 1, endOffset).trim();

    logger.debug("[DIAMONDQ Profile Activator] Keyword: |" + keyword + "| -> |" + args + "|");

    /* Handle the keyword */

    if ("or".equalsIgnoreCase(keyword)) {

      /* Split the arg into a set of paths */

      List<String> split = splitArgs(args);
      for (String arg : split) {
        if (recursiveProcess(pProfile, pContext, pProblems, pPropertyLocation, arg) == true) {
          logger.debug("[DIAMONDQ Profile Activator] OR child returned true, so OR is true");
          return true;
        }
      }
      logger.debug("[DIAMONDQ Profile Activator] no OR child returned true, so OR is false");
      return false;
    }
    else if ("and".equalsIgnoreCase(keyword)) {

      /* Split the arg into a set of paths */

      List<String> split = splitArgs(args);

      for (String arg : split) {
        if (recursiveProcess(pProfile, pContext, pProblems, pPropertyLocation, arg) == false) {
          logger.debug("[DIAMONDQ Profile Activator] AND child returned false, so AND is false");
          return false;
        }
      }
      logger.debug("[DIAMONDQ Profile Activator] no AND child returned false, so AND is true");
      return true;
    }
    else if ("file".equalsIgnoreCase(keyword)) {

      /* Resolve the file to see if it exists */

      String filePath = resolveDir(args, pProfile, pContext, pProblems, pPropertyLocation);
      if (filePath == null)
        return false;
      File file = new File(filePath);
      if (file.exists() == true) {
        logger.debug("[DIAMONDQ Profile Activator] file exists so true -> " + filePath);
        return true;
      }
      else {
        logger.debug("[DIAMONDQ Profile Activator] file missing so false -> " + filePath);
        return false;
      }
    }
    else if ("missing".equalsIgnoreCase(keyword)) {

      /* Resolve the file to see if it exists */

      String filePath = resolveDir(args, pProfile, pContext, pProblems, pPropertyLocation);
      if (filePath == null)
        return false;
      File file = new File(filePath);
      if (file.exists() == true) {
        logger.debug("[DIAMONDQ Profile Activator] file exists so false -> " + filePath);
        return false;
      }
      else {
        logger.debug("[DIAMONDQ Profile Activator] file missing so true -> " + filePath);
        return true;
      }
    }
    else if ("property".equalsIgnoreCase(keyword)) {

      /* See if there is an = operator */

      int eqOffset = args.indexOf('=');
      String propValue;
      String propKey;
      if (eqOffset == -1) {
        propKey = args;
        propValue = null;
      }
      else {
        propKey = args.substring(0, eqOffset).trim();
        propValue = args.substring(eqOffset + 1).trim();
      }

      /* Check if the ! is present in the key */

      boolean reverse;
      if (propKey.startsWith("!")) {
        reverse = true;
        propKey = propKey.substring(1);
      }
      else
        reverse = false;

      /* Now look for the property */

      String sysValue = pContext.getUserProperties().get(propKey);
      if (sysValue == null)
        sysValue = pContext.getSystemProperties().get(propKey);

      if (StringUtils.isNotEmpty(sysValue)) {
        if (propValue == null) {
          if (reverse == true) {
            logger.debug("[DIAMONDQ Profile Activator] property \"" + propKey + "\" exists so false");
            return false;
          }
          logger.debug("[DIAMONDQ Profile Activator] property \"" + propKey + "\" exists so true");
          return true;
        }
        else {
          if (propValue.equals(sysValue)) {
            if (reverse == true) {
              logger.debug("[DIAMONDQ Profile Activator] property \"" + propKey + "\" -> \"" + sysValue
                + "\" equals to \"" + propValue + "\" so false");
              return false;
            }
            logger.debug("[DIAMONDQ Profile Activator] property \"" + propKey + "\" -> \"" + sysValue
              + "\" equals to \"" + propValue + "\" so true");
            return true;
          }
          else {
            if (reverse == true) {
              logger.debug("[DIAMONDQ Profile Activator] property \"" + propKey + "\" -> \"" + sysValue
                + "\" not equals to \"" + propValue + "\" so true");
              return true;
            }
            logger.debug("[DIAMONDQ Profile Activator] property \"" + propKey + "\" -> \"" + sysValue
              + "\" not equals to \"" + propValue + "\" so false");
            return false;
          }
        }
      }
      else {
        if (reverse == true) {
          logger.debug("[DIAMONDQ Profile Activator] property \"" + propKey + "\" does not exist so true");
          return true;
        }
        logger.debug("[DIAMONDQ Profile Activator] property \"" + propKey + "\" does not exist so false");
        return false;
      }
    }
    else if ("type".equalsIgnoreCase(keyword)) {

      /* Check to see if there is a file with the prefix "type-{args}" present */

      String filePath = resolveDir("profiles", pProfile, pContext, pProblems, pPropertyLocation);
      if (filePath == null)
        return false;
      File profilesDir = new File(filePath);
      if (profilesDir.exists() == true)
        for (String testFile : profilesDir.list()) {
          if (testFile.startsWith("type-" + args)) {
            logger.debug("[DIAMONDQ Profile Activator] type \"" + testFile + "\" exists so true");
            return true;
          }
        }
      logger.debug("[DIAMONDQ Profile Activator] no type \"type-" + args + "\" exists so false");
      return false;
    }
    else if ("jdk".equalsIgnoreCase(keyword)) {

      /* Check to see what type-java-XX is present */

      int javaVer = -1;
      String filePath = resolveDir("profiles", pProfile, pContext, pProblems, pPropertyLocation);
      if (filePath == null)
        return false;
      File profilesDir = new File(filePath);
      if (profilesDir.exists() == true) {
        for (String testFile : profilesDir.list()) {
          if (testFile.startsWith("type-java-"))
            javaVer = Integer.parseInt(testFile.substring(10));
        }
        if (javaVer == -1) {
          logger.debug("[DIAMONDQ Profile Activator] No profiles/type-java-XXX present when requesting a jdk so false");
          return false;
        }
        if (args.startsWith("<=") || args.startsWith("=<")) {
          int testVer = Integer.parseInt(args.substring(2).trim());
          if (javaVer <= testVer) {
            logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " <= " + testVer + " so true");
            return true;
          }
        }
        else if (args.startsWith("<")) {
          int testVer = Integer.parseInt(args.substring(1).trim());
          if (javaVer < testVer) {
            logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " < " + testVer + " so true");
            return true;
          }
        }
        else if (args.startsWith(">=") || args.startsWith("=>")) {
          int testVer = Integer.parseInt(args.substring(2).trim());
          if (javaVer >= testVer) {
            logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " >= " + testVer + " so true");
            return true;
          }
        }
        else if (args.startsWith(">")) {
          int testVer = Integer.parseInt(args.substring(1).trim());
          if (javaVer > testVer) {
            logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " > " + testVer + " so true");
            return true;
          }
        }
        else if (args.startsWith("=")) {
          int testVer = Integer.parseInt(args.substring(1).trim());
          if (javaVer == testVer) {
            logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " == " + testVer + " so true");
            return true;
          }
        }
        else {
          int testVer = Integer.parseInt(args.trim());
          if (javaVer == testVer) {
            logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " == " + testVer + " so true");
            return true;
          }
        }
      }
      logger.debug("[DIAMONDQ Profile Activator] jdk is false");
      return false;
    }
    else {
      pProblems.add(new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
        .setMessage("Unrecognized script keyword \"" + keyword + "\" when activating the profile " + pProfile.getId())
        .setLocation(pPropertyLocation));
      return false;
    }
  }

  private String resolveDir(String pPath, Profile pProfile, ProfileActivationContext pContext,
    ModelProblemCollector pProblems, InputLocation pPropertyLocation) {
    RegexBasedInterpolator interpolator = new RegexBasedInterpolator();

    final File basedir = pContext.getProjectDirectory();

    if (basedir != null) {
      interpolator.addValueSource(new AbstractValueSource(false) {
        @Override
        public Object getValue(String expression) {
          /*
           * NOTE: We intentionally only support ${basedir} and not ${project.basedir} as the latter form would suggest
           * that other project.* expressions can be used which is however beyond the design.
           */
          if ("basedir".equals(expression)) {
            return basedir.getAbsolutePath();
          }
          return null;
        }
      });
    }
    else if (pPath.contains("${basedir}")) {
      return null;
    }

    interpolator.addValueSource(new MapBasedValueSource(pContext.getProjectProperties()));

    interpolator.addValueSource(new MapBasedValueSource(pContext.getUserProperties()));

    interpolator.addValueSource(new MapBasedValueSource(pContext.getSystemProperties()));

    try {
      pPath = interpolator.interpolate(pPath, "");
    }
    catch (Exception e) {
      pProblems.add(new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
        .setMessage(
          "Failed to interpolate file location " + pPath + " for profile " + pProfile.getId() + ": " + e.getMessage())
        .setLocation(pPropertyLocation).setException(e));
      return null;
    }

    pPath = pathTranslator.alignToBaseDirectory(pPath, basedir);

    return pPath;
  }

  private List<String> splitArgs(String pArgs) {
    List<String> result = new ArrayList<>();
    char[] charArray = pArgs.toCharArray();
    int size = charArray.length;
    int depth = 0;
    int start = 0;
    for (int i = 0; i < size; i++) {
      char ch = charArray[i];
      if (ch == '(')
        depth++;
      else if (ch == ')')
        depth--;
      else if ((ch == ',') && (depth == 0)) {
        result.add(new String(charArray, start, i - start).trim());
        start = i + 1;
      }
    }
    if (depth == 0)
      result.add(new String(charArray, start, size - start).trim());
    logger.debug("[DIAMONDQ Profile Activator] splitArgs(" + pArgs + ") -> " + result);
    return result;
  }
}
