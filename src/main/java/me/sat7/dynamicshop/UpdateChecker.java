package me.sat7.dynamicshop;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

/**
 * Utility class to check for plugin updates by querying the latest release
 * tag from the user's dedicated GitHub repository.
 */
public class UpdateChecker
{
    // Configuration to target the user's GitHub repository
    private static final String GITHUB_OWNER = "Cupjok";
    private static final String GITHUB_REPO = "DynamicShop3";
    private static final String GITHUB_RELEASES_URL = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    // Spigot Project ID is kept but not used for update checking anymore.
    public static final int PROJECT_ID = 65603;

    private final JavaPlugin plugin;
    private final int resourceId;

    public UpdateChecker(JavaPlugin plugin, int resourceId)
    {
        this.plugin = plugin;
        // resourceId (Spigot ID) is no longer required for version check, but kept for constructor consistency
        this.resourceId = resourceId;
    }

    /**
     * Asynchronously retrieves the latest version string from the GitHub API (latest release tag).
     * This replaces the Spigot API call entirely.
     * @param consumer A Consumer to process the latest version string.
     */
    public void getVersion(final Consumer<String> consumer)
    {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () ->
        {
            try {
                URL url = new URL(GITHUB_RELEASES_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                // GitHub API requires a User-Agent header
                connection.setRequestProperty("User-Agent", "DynamicShop-UpdateChecker");

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    // Simple JSON parsing to extract 'tag_name'
                    String json = response.toString();
                    if (json.contains("\"tag_name\"")) {
                        int start = json.indexOf("\"tag_name\"") + 12; // Start after "tag_name":"
                        int end = json.indexOf("\"", start);
                        String latestVersion = json.substring(start, end);

                        // Strip leading 'v' (e.g., v3.22.0 -> 3.22.0) for proper comparison
                        if (latestVersion.toLowerCase().startsWith("v")) {
                            latestVersion = latestVersion.substring(1);
                        }

                        consumer.accept(latestVersion);
                    } else {
                        plugin.getLogger().warning("Could not find 'tag_name' in GitHub API response. Ensure a Release exists.");
                    }
                }
            } catch (IOException exception)
            {
                plugin.getLogger().info("Unable to check for updates from GitHub: " + exception.getMessage());
            }
        });
    }

    /**
     * Checks if the plugin is running the latest version and logs a warning
     * with the GitHub link if an update is available.
     */
    public void checkUpdate()
    {
        getVersion(latestVersion ->
        {
            String currentVersion = plugin.getDescription().getVersion();

            // Only warn if the current version is strictly OLDER than the latest release tag on GitHub
            if (compareVersions(currentVersion, latestVersion) < 0)
            {
                // Current version is older than the latest GitHub release
                plugin.getLogger().warning("================================================");
                plugin.getLogger().warning(plugin.getName() + " is outdated!");
                plugin.getLogger().warning("New version available: " + latestVersion);
                // Directs users to your specific GitHub Releases page for the update
                plugin.getLogger().warning("Download the latest version here: " + getResourceUrl() + "/releases");
                plugin.getLogger().warning("================================================");
            }
            else if (compareVersions(currentVersion, latestVersion) > 0)
            {
                // Current version is newer (e.g., a development build)
                plugin.getLogger().info("Running a potentially newer version (" + currentVersion + ") than the latest release (" + latestVersion + ").");
            }
            else
            {
                // Versions are identical
                plugin.getLogger().info(plugin.getName() + " is up to date (" + currentVersion + ").");
            }
        });
    }

    /**
     * Compares two semantic version strings (e.g., v1.2.3).
     * @return -1 if v1 is older, 1 if v1 is newer, and 0 if they are equal.
     * * IMPORTANT FIX: Changed from private to public static to allow access from DynamicShop.java.
     */
    public static int compareVersions(String v1, String v2)
    {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++)
        {
            int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;

            if (num1 < num2) return -1;
            if (num1 > num2) return 1;
        }
        return 0;
    }

    /**
     * Returns the GitHub repository URL.
     * @return The GitHub repository URL.
     */
    public static String getResourceUrl()
    {
        return "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO;
    }
}