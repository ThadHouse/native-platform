import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublishingExtension;

public class PublishSnapshotPlugin implements Plugin<Project> {
    private static final String SNAPSHOT_REPOSITORY_NAME = "Snapshot";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(BasePublishPlugin.class);
        final BintrayCredentials credentials = project.getExtensions().getByType(BintrayCredentials.class);

        project.getExtensions().configure(
                PublishingExtension.class,
                extension -> extension.getRepositories().maven(repo -> {
                    repo.setUrl(ReleasePlugin.SNAPSHOT_REPOSITORY_URL);
                    repo.setName(SNAPSHOT_REPOSITORY_NAME);
                    repo.credentials(passwordCredentials -> {
                        passwordCredentials.setUsername(credentials.getUserName());
                        passwordCredentials.setPassword(credentials.getApiKey());
                    });
                }));
    }

    public static String uploadTaskName(Publication publication) {
        return BasePublishPlugin.publishTaskName(publication, SNAPSHOT_REPOSITORY_NAME);
    }
}
