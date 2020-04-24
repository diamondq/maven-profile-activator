package com.diamondq.maven.activator;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.eclipse.sisu.Priority;

/**
 * Profile selector which combines profiles activated by custom and default activators. Overrides "default" provider.
 */
@Component(role = ProfileSelector.class, hint = "extra")
@Priority(999)
public class DiamondQProfileSelector extends DefaultProfileSelector {

  @Requirement
  protected Logger                                logger;

  @Requirement(role = ProfileActivator.class)
  protected List<ProfileActivator>                activatorList = new ArrayList<>();

  @Requirement
  private PathTranslator                          pathTranslator;

  /**
   * This holds a set of dependencies that need to be checked whenever the profile activation context changes. If the
   * dependencies 'fail', then the cachedActiveProfiles is no longer valid
   */
  private final List<Dependency>                  dependencies;

  /**
   * A pointer to the last context
   */
  private WeakReference<ProfileActivationContext> lastProfileActiveContext;

  /**
   * The set of profiles that are known to be active
   */
  private final Set<String>                       cachedActiveProfiles;

  /**
   * The set of profiles that are known to be inactive
   */
  private final Set<String>                       cachedInactiveProfiles;

  private final AtomicBoolean                     debugReport   = new AtomicBoolean(false);

  public DiamondQProfileSelector() {
    dependencies = new ArrayList<>();
    lastProfileActiveContext = new WeakReference<ProfileActivationContext>(null);
    cachedActiveProfiles = new HashSet<>();
    cachedInactiveProfiles = new HashSet<>();
  }

  /**
   * Profiles activated by both custom and default activators.
   */
  @Override
  public List<Profile> getActiveProfiles(Collection<Profile> profiles, ProfileActivationContext context,
    ModelProblemCollector problems) {

    synchronized (this) {

      /* Get a debug flag from the system properties */

      boolean selectorDebug =
        Boolean.parseBoolean(context.getSystemProperties().getOrDefault("DiamondQProfileSelectorDebug", "false"));

      if (debugReport.compareAndSet(false, true) == true)
        logger.debug(
          "[DIAMONDQ Profile Activator] Use the -DDiamondQProfileSelectorDebug=true flag to get very detailed tracing information");

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

      /* Check if the context has changed */

      ProfileActivationContext lastContext = lastProfileActiveContext.get();
      if (context != lastContext) {
        boolean valid = true;

        if (selectorDebug == true)
          logger.info("[DIAMONDQ Profile Activator] Context has changed. Checking all dependencies...");

        /* We need to check all the dependencies */

        for (Dependency dependency : dependencies)
          if (dependency.isValid(updatedContext, null) == false) {
            valid = false;
            break;
          }

        if (valid == false) {
          cachedActiveProfiles.clear();
          cachedInactiveProfiles.clear();
          dependencies.clear();
          if (selectorDebug == true)
            logger.info(
              "[DIAMONDQ Profile Activator] A dependency is no longer valid. All cached profiles have been cleared");
        }
        else {
          if (selectorDebug == true)
            logger.info("[DIAMONDQ Profile Activator] All dependencies are still valid.");
        }
        lastProfileActiveContext = new WeakReference<ProfileActivationContext>(context);
      }

      /* Log the context profile ids */

      if (selectorDebug == true) {
        logger.info("[DIAMONDQ Profile Activator] Profiles: " + Arrays.toString(profiles.toArray()));
        logger.info("[DIAMONDQ Profile Activator] Context: " + System.identityHashCode(context));
        logger.info("[DIAMONDQ Profile Activator] Context Dir: " + context.getProjectDirectory().toString());
        logger.info("[DIAMONDQ Profile Activator] Context: Active Profile Ids: "
          + Arrays.toString(updatedContext.getActiveProfileIds().toArray()));
        logger.info("[DIAMONDQ Profile Activator] Context: Inactive Profile Ids: "
          + Arrays.toString(updatedContext.getInactiveProfileIds().toArray()));
      }

      List<Profile> activeProfileList = new ArrayList<>();
      for (Profile profile : profiles) {
        if (selectorDebug == true)
          logger.info("[DIAMONDQ Profile Activator] Checking if profile " + profile.getId() + " is active?");

        /* Check if this profile should be activated */

        if (hasActive(selectorDebug, profile, updatedContext, problems)) {
          if (selectorDebug == true)
            logger.info("[DIAMONDQ Profile Activator] Activating profile " + profile.getId());
          activeProfileList.add(profile);
        }
      }
      activeProfileList.addAll(super.getActiveProfiles(profiles, updatedContext, problems));
      if ((logger.isDebugEnabled() == true) && (activeProfileList.isEmpty() == false))
        logger
          .debug("[DIAMONDQ Profile Activator] Activated profiles: " + Arrays.toString(activeProfileList.toArray()));
      return activeProfileList;
    }
  }

  protected boolean hasActive(boolean pSelectorDebug, Profile profile, ProfileActivationContext context,
    ModelProblemCollector problems) {

    /* Start by checking the cache */

    String profileId = profile.getId();
    if (cachedActiveProfiles.contains(profileId) == true) {
      if (pSelectorDebug == true)
        logger.debug("[DIAMONDQ Profile Activator] Cached active profile found");
      return true;
    }
    if (cachedInactiveProfiles.contains(profileId) == true) {
      if (pSelectorDebug == true)
        logger.debug("[DIAMONDQ Profile Activator] Cached inactive profile found");
      return false;
    }

    /* Check if this is one of the profiles that we can verify? */

    boolean result = false;
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

          if (pSelectorDebug == true)
            logger.debug("[DIAMONDQ Profile Activator] Resolving " + script);

          /* Recursively check */

          result = recursiveProcess(pSelectorDebug, profile, context, problems, property.getLocation(""), script);
        }
      }
    }

    /* Cache the result */

    if (result == true)
      cachedActiveProfiles.add(profileId);
    else
      cachedInactiveProfiles.add(profileId);
    return result;
  }

  private boolean recursiveProcess(boolean pSelectorDebug, Profile pProfile, ProfileActivationContext pContext,
    ModelProblemCollector pProblems, InputLocation pPropertyLocation, String pScript) {
    if (pSelectorDebug == true)
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

    if (pSelectorDebug == true)
      logger.debug("[DIAMONDQ Profile Activator] Keyword: |" + keyword + "| -> |" + args + "|");

    /* Handle the keyword */

    if ("or".equalsIgnoreCase(keyword)) {

      /* Split the arg into a set of paths */

      List<String> split = splitArgs(pSelectorDebug, args);
      for (String arg : split) {
        if (recursiveProcess(pSelectorDebug, pProfile, pContext, pProblems, pPropertyLocation, arg) == true) {
          if (pSelectorDebug == true)
            logger.debug("[DIAMONDQ Profile Activator] OR child returned true, so OR is true");
          return true;
        }
      }
      if (pSelectorDebug == true)
        logger.debug("[DIAMONDQ Profile Activator] no OR child returned true, so OR is false");
      return false;
    }
    else if ("and".equalsIgnoreCase(keyword)) {

      /* Split the arg into a set of paths */

      List<String> split = splitArgs(pSelectorDebug, args);

      for (String arg : split) {
        if (recursiveProcess(pSelectorDebug, pProfile, pContext, pProblems, pPropertyLocation, arg) == false) {
          if (pSelectorDebug == true)
            logger.debug("[DIAMONDQ Profile Activator] AND child returned false, so AND is false");
          return false;
        }
      }
      if (pSelectorDebug == true)
        logger.debug("[DIAMONDQ Profile Activator] no AND child returned false, so AND is true");
      return true;
    }
    else if ("not".equalsIgnoreCase(keyword)) {
      if (recursiveProcess(pSelectorDebug, pProfile, pContext, pProblems, pPropertyLocation, args) == true) {
        if (pSelectorDebug == true)
          logger.debug("[DIAMONDQ Profile Activator] NOT child returned true, so NOT is false");
        return false;
      }
      if (pSelectorDebug == true)
        logger.debug("[DIAMONDQ Profile Activator] NOT child returned false, so NOT is true");
      return true;
    }
    else if ("file".equalsIgnoreCase(keyword)) {

      /* Resolve the file to see if it exists */

      String filePath = resolveDir(args, pProfile, pContext, pProblems, pPropertyLocation);
      if (filePath == null) {
        dependencies.add(Dependency.onFail());
        return false;
      }
      File file = new File(filePath);
      dependencies.add(Dependency.onFile(file));
      if (file.exists() == true) {
        if (pSelectorDebug == true)
          logger.debug("[DIAMONDQ Profile Activator] file exists so true -> " + filePath);
        return true;
      }
      else {
        if (pSelectorDebug == true)
          logger.debug("[DIAMONDQ Profile Activator] file missing so false -> " + filePath);
        return false;
      }
    }
    else if ("missing".equalsIgnoreCase(keyword)) {

      /* Resolve the file to see if it exists */

      String filePath = resolveDir(args, pProfile, pContext, pProblems, pPropertyLocation);
      if (filePath == null) {
        dependencies.add(Dependency.onFail());
        return false;
      }
      File file = new File(filePath);
      dependencies.add(Dependency.onFile(file));
      if (file.exists() == true) {
        if (pSelectorDebug == true)
          logger.debug("[DIAMONDQ Profile Activator] file exists so false -> " + filePath);
        return false;
      }
      else {
        if (pSelectorDebug == true)
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

      boolean userProperty = true;
      String sysValue = pContext.getUserProperties().get(propKey);
      if (sysValue == null) {
        userProperty = false;
        sysValue = pContext.getSystemProperties().get(propKey);
      }

      Dependency dep = Dependency.onProperty(userProperty, propKey, propValue, reverse);
      return dep.isValid(pContext, pSelectorDebug == true ? logger : null);
    }
    else if ("type".equalsIgnoreCase(keyword)) {

      /* Check to see if there is a file with the prefix "type-{args}" present */

      String filePath = resolveDir("profiles", pProfile, pContext, pProblems, pPropertyLocation);
      if (filePath == null) {
        dependencies.add(Dependency.onFail());
        return false;
      }
      File profilesDir = new File(filePath);
      String prefix = "type-" + args;
      if (profilesDir.exists() == true) {
        for (File testFile : profilesDir.listFiles()) {
          if (testFile.getName().startsWith(prefix)) {
            if (pSelectorDebug == true)
              logger.debug("[DIAMONDQ Profile Activator] type \"" + testFile + "\" exists so true");
            dependencies.add(Dependency.onFile(testFile));
            return true;
          }
        }
        if (pSelectorDebug == true)
          logger.debug("[DIAMONDQ Profile Activator] no type \"" + prefix + "\" exists so false");
        dependencies.add(Dependency.onNoStartsWith(profilesDir, prefix));
        return false;
      }
      else {
        if (pSelectorDebug == true)
          logger.debug("[DIAMONDQ Profile Activator] profiles dir \"" + filePath + "\" doesn't exists so false");
        dependencies.add(Dependency.onFile(profilesDir));
        return false;
      }
    }
    else if ("jdk".equalsIgnoreCase(keyword)) {

      /* Check to see what type-java-XX is present */

      int javaVer = -1;
      String filePath = resolveDir("profiles", pProfile, pContext, pProblems, pPropertyLocation);
      if (filePath == null) {
        dependencies.add(Dependency.onFail());
        return false;
      }
      File profilesDir = new File(filePath);
      if (profilesDir.exists() == true) {
        File matchFile = null;
        for (File testFile : profilesDir.listFiles()) {
          if (testFile.getName().startsWith("type-java-")) {
            matchFile = testFile;
            javaVer = Integer.parseInt(testFile.getName().substring(10));
          }
        }
        if (javaVer == -1) {
          if (pSelectorDebug == true)
            logger
              .debug("[DIAMONDQ Profile Activator] No profiles/type-java-XXX present when requesting a jdk so false");
          dependencies.add(Dependency.onNoStartsWith(profilesDir, "type-java-"));
          return false;
        }
        dependencies.add(Dependency.onFile(matchFile));
        if (args.startsWith("<=") || args.startsWith("=<")) {
          int testVer = Integer.parseInt(args.substring(2).trim());
          if (javaVer <= testVer) {
            if (pSelectorDebug == true)
              logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " <= " + testVer + " so true");
            return true;
          }
        }
        else if (args.startsWith("<")) {
          int testVer = Integer.parseInt(args.substring(1).trim());
          if (javaVer < testVer) {
            if (pSelectorDebug == true)
              logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " < " + testVer + " so true");
            dependencies.add(Dependency.onFile(matchFile));
            return true;
          }
        }
        else if (args.startsWith(">=") || args.startsWith("=>")) {
          int testVer = Integer.parseInt(args.substring(2).trim());
          if (javaVer >= testVer) {
            if (pSelectorDebug == true)
              logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " >= " + testVer + " so true");
            dependencies.add(Dependency.onFile(matchFile));
            return true;
          }
        }
        else if (args.startsWith(">")) {
          int testVer = Integer.parseInt(args.substring(1).trim());
          if (javaVer > testVer) {
            if (pSelectorDebug == true)
              logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " > " + testVer + " so true");
            dependencies.add(Dependency.onFile(matchFile));
            return true;
          }
        }
        else if (args.startsWith("=")) {
          int testVer = Integer.parseInt(args.substring(1).trim());
          if (javaVer == testVer) {
            if (pSelectorDebug == true)
              logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " == " + testVer + " so true");
            dependencies.add(Dependency.onFile(matchFile));
            return true;
          }
        }
        else {
          int testVer = Integer.parseInt(args.trim());
          if (javaVer == testVer) {
            if (pSelectorDebug == true)
              logger.debug("[DIAMONDQ Profile Activator] jdk " + javaVer + " == " + testVer + " so true");
            dependencies.add(Dependency.onFile(matchFile));
            return true;
          }
        }
        if (pSelectorDebug == true)
          logger.debug("[DIAMONDQ Profile Activator] jdk is false");
        return false;
      }
      else {
        if (pSelectorDebug == true)
          logger.debug("[DIAMONDQ Profile Activator] profiles dir \"" + filePath + "\" doesn't exists so false");
        dependencies.add(Dependency.onFile(profilesDir));
        return false;
      }
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
    boolean containsBaseDir = pPath.contains("${basedir}");

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
    else {
      if (containsBaseDir) {
        return null;
      }
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

    if ((Paths.get(pPath).isAbsolute() == false) || (containsBaseDir == true))
      dependencies.add(Dependency.onProjectDir(basedir));
    pPath = pathTranslator.alignToBaseDirectory(pPath, basedir);

    return pPath;
  }

  private List<String> splitArgs(boolean pSelectorDebug, String pArgs) {
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
    if (pSelectorDebug == true)
      logger.debug("[DIAMONDQ Profile Activator] splitArgs(" + pArgs + ") -> " + result);
    return result;
  }
}
