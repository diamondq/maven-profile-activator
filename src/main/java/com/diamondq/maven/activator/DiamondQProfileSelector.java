package com.diamondq.maven.activator;

import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.path.PathTranslator;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.activation.ProfileActivator;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.sisu.Priority;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Profile selector which combines profiles activated by custom and default activators. Overrides "default" provider.
 */
@Named("extra")
@Priority(999)
@Singleton
public class DiamondQProfileSelector extends DefaultProfileSelector {

    private final CommonProfileSelector commonSelector;
    private final AtomicBoolean debugReport = new AtomicBoolean(false);

    @Inject
    public DiamondQProfileSelector(Logger pLogger, PathTranslator pPathTranslator,
                                   List<ProfileActivator> pActivatorList) {
        commonSelector = new CommonProfileSelector(pActivatorList, new PlexusActivatorLogger(pLogger), pPathTranslator);
    }

    /**
     * Profiles activated by both custom and default activators.
     */
    @Override
    public List<Profile> getActiveProfiles(Collection<Profile> profiles, ProfileActivationContext context,
                                           ModelProblemCollector problems) {

        synchronized (this) {

            if (debugReport.compareAndSet(false, true)) commonSelector.logger.debug(
                    "[DIAMONDQ Profile Activator] Use the -DDiamondQProfileSelectorDebug=true flag to get very detailed tracing"
                            + " information");

            return commonSelector.select(profiles, context, problems);
        }
    }

}
