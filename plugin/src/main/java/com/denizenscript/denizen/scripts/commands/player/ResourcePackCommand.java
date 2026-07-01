package com.denizenscript.denizen.scripts.commands.player;

import com.denizenscript.denizen.Denizen;
import com.denizenscript.denizen.nms.NMSHandler;
import com.denizenscript.denizen.nms.NMSVersion;
import com.denizenscript.denizen.objects.PlayerTag;
import com.denizenscript.denizen.utilities.PaperAPITools;
import com.denizenscript.denizen.utilities.Utilities;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;
import com.denizenscript.denizencore.scripts.commands.Holdable;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ResourcePackCommand extends AbstractCommand implements Holdable {

    public ResourcePackCommand() {
        setName("resourcepack");
        setSyntax("resourcepack ({set}/add/remove) (id:<id>) (url:<url>) (hash:<hash>) (forced) (prompt:<text>) (targets:<player>|...)");
        setRequiredArguments(1, 7);
        setPrefixesHandled("id", "url", "hash", "prompt", "target", "targets", "t");
        setBooleansHandled("forced");
        isProcedural = false;
    }

    // <--[command]
    // @Name ResourcePack
    // @Syntax resourcepack ({set}/add/remove) (id:<id>) (url:<url>) (hash:<hash>) (forced) (prompt:<text>) (targets:<player>|...)
    // @Required 2
    // @Maximum 5
    // @Short Prompts a player to download a server resource pack.
    // @group player
    //
    // @Description
    // Sets the current resource pack by specifying a valid URL to a resource pack.
    //
    // Optionally, you can send the player additional resource packs by using the "add" argument.
    // The "id" argument allows you to overwrite a specific resource pack or remove one with "remove" argument.
    //
    // The player will be prompted to download the pack, with the optional prompt text or a default vanilla message.
    // Once a player says "yes" once, all future packs will be automatically downloaded. If the player selects "no" once, all future packs will automatically be rejected.
    // Players can change the automatic setting from their server list in the main menu.
    //
    // Use "hash:" to specify a 40-character (20 byte) hexadecimal SHA-1 hash value (without '0x') for the resource pack to prevent redownloading cached data.
    // Specifying a hash is required, though you can get away with copy/pasting a fake value if you don't care for the consequences.
    // There are a variety of tools to generate the real hash, such as the `sha1sum` command on Linux, or using the 7-Zip GUI's Checksum option on Windows.
    // You can alternatively specify "hash:stream" to have Denizen asynchronously stream the resource pack from the URL and calculate its SHA-1 hash.
    // This avoids saving the pack to disk, but still requires reading the full pack from the URL.
    //
    // Specify "forced" to tell the vanilla client they must accept the pack or quit the server. Hacked clients may still bypass this requirement.
    //
    // "Forced" and "prompt" inputs only work on Paper servers.
    //
    // Optionally specify players to send the pack to. If unspecified, will use the linked player.
    //
    // See also <@link event resource pack status>.
    //
    // @Tags
    // None
    //
    // @Usage
    // Use to set a resource pack with a pre-known hash.
    // - resourcepack url:https://example.com/pack.zip hash:0102030405060708090a0b0c0d0e0f1112131415
    //
    // @Usage
    // Use to stream a resource pack from its URL to calculate the hash automatically.
    // - resourcepack url:https://example.com/pack.zip hash:stream
    //
    // @Usage
    // Use to send multiple resource packs to a player.
    // - resourcepack add id:first_pack url:https://example.com/pack1.zip hash:0102030405060708090a0b0c0d0e0f1112131415
    // - resourcepack add id:second_pack url:https://example.com/pack2.zip hash:0102030405060708090a0b0c0d0e0f1112131415
    //
    // @Usage
    // Use to remove all resource packs from all online players.
    // - resourcepack remove targets:<server.online_players>
    // -->

    public enum Action { SET, ADD, REMOVE }

    public static ConcurrentHashMap<String, CompletableFuture<String>> currentHashStreams = new ConcurrentHashMap<>();

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {
        for (Argument arg : scriptEntry) {
            if (!scriptEntry.hasObject("action") && arg.matchesEnum(Action.class)) {
                scriptEntry.addObject("action", Action.valueOf(arg.getValue().toUpperCase()));
            }
            else if (!scriptEntry.hasObject("id") && arg.matchesPrefix("id")) {
                scriptEntry.addObject("id", arg.asElement());
            }
            else if (!scriptEntry.hasObject("url") && arg.matchesPrefix("url")) {
                scriptEntry.addObject("url", arg.asElement());
            }
            else if (!scriptEntry.hasObject("hash") && arg.matchesPrefix("hash")) {
                scriptEntry.addObject("hash", arg.asElement());
            }
            else if (!scriptEntry.hasObject("prompt") && arg.matchesPrefix("prompt")) {
                scriptEntry.addObject("prompt", arg.asElement());
            }
            else if (!scriptEntry.hasObject("targets") && arg.matchesPrefix("target", "targets", "t") && arg.matchesArgumentList(PlayerTag.class)) {
                scriptEntry.addObject("targets", arg.asType(ListTag.class).filter(PlayerTag.class, scriptEntry));
            }
            else {
                arg.reportUnhandled();
            }
        }
        scriptEntry.defaultObject("action", Action.SET);
    }

    @Override
    public void execute(ScriptEntry scriptEntry) {
        Action action = (Action) scriptEntry.getObject("action");
        ElementTag idElement = scriptEntry.getElement("id");
        ElementTag urlElement = scriptEntry.getElement("url");
        ElementTag hashElement = scriptEntry.getElement("hash");
        ElementTag promptElement = scriptEntry.getElement("prompt");
        String id = idElement == null ? null : idElement.asString();
        String url = urlElement == null ? null : urlElement.asString();
        String hash = hashElement == null ? null : hashElement.asString();
        String prompt = promptElement == null ? null : promptElement.asString();
        List<PlayerTag> targets = (List<PlayerTag>) scriptEntry.getObject("targets");
        boolean forced = scriptEntry.argAsBoolean("forced");
        if (targets == null) {
            if (!Utilities.entryHasPlayer(scriptEntry)) {
                Debug.echoError("Must specify an online player!");
                scriptEntry.setFinished(true);
                return;
            }
            targets = List.of(Utilities.getEntryPlayer(scriptEntry));
        }
        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), db("action", action.name()), db("id", id), db("url", url), db("hash", hash), db("forced", forced), db("prompt", prompt), db("targets", targets));
        }
        if (action == Action.ADD || action == Action.SET) {
            if (url == null || hash == null) {
                Debug.echoError("Must specify both a resource pack URL and hash!");
                scriptEntry.setFinished(true);
                return;
            }
            if (CoreUtilities.equalsIgnoreCase(hash, "stream")) {
                final List<PlayerTag> finalTargets = targets;
                final String finalUrl = url;
                final String finalId = id;
                final String finalPrompt = prompt;
                streamHash(finalUrl).whenComplete((streamedHash, error) -> Bukkit.getScheduler().runTask(Denizen.getInstance(), () -> {
                    if (error != null) {
                        Debug.echoError(scriptEntry, "Failed to stream resource pack hash from URL '" + finalUrl + "'.");
                        Debug.echoError(scriptEntry, error);
                    }
                    else {
                        applyResourcePack(action, finalId, finalUrl, streamedHash, finalPrompt, finalTargets, forced);
                    }
                    scriptEntry.setFinished(true);
                }));
                return;
            }
            if (hash.length() != 40) {
                Debug.echoError("Invalid resource_pack hash. Should be 40 characters of hexadecimal data.");
                scriptEntry.setFinished(true);
                return;
            }
        }
        applyResourcePack(action, id, url, hash, prompt, targets, forced);
        scriptEntry.setFinished(true);
    }

    public static void applyResourcePack(Action action, String id, String url, String hash, String prompt, List<PlayerTag> targets, boolean forced) {
        switch (action) {
            case SET -> {
                UUID packUUID = id == null ? null : parseUUID(id);
                for (PlayerTag player : targets) {
                    if (checkOnline(player)) {
                        PaperAPITools.instance.setResourcePack(player.getPlayerEntity(), url, hash, forced, prompt, packUUID);
                    }
                }
            }
            case ADD -> {
                UUID packUUID = id == null ? UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)) : parseUUID(id);
                for (PlayerTag player : targets) {
                    if (checkOnline(player)) {
                        PaperAPITools.instance.addResourcePack(player.getPlayerEntity(), url, hash, forced, prompt, packUUID);
                    }
                }
            }
            case REMOVE -> {
                if (id == null) {
                    for (PlayerTag player : targets) {
                        if (checkOnline(player)) {
                            player.getPlayerEntity().removeResourcePacks();
                        }
                    }
                }
                else {
                    UUID packUUID = parseUUID(id);
                    for (PlayerTag player : targets) {
                        if (checkOnline(player)) {
                            player.getPlayerEntity().removeResourcePack(packUUID);
                        }
                    }
                }
            }
        }
    }

    public static CompletableFuture<String> streamHash(String url) {
        return currentHashStreams.computeIfAbsent(url, (key) -> {
            CompletableFuture<String> result = new CompletableFuture<>();
            Bukkit.getScheduler().runTaskAsynchronously(Denizen.getInstance(), () -> {
                try {
                    result.complete(calculateSha1ForUrl(key));
                }
                catch (Throwable ex) {
                    result.completeExceptionally(ex);
                }
                finally {
                    currentHashStreams.remove(key, result);
                }
            });
            return result;
        });
    }

    public static String calculateSha1ForUrl(String url) throws IOException, NoSuchAlgorithmException {
        URL resourceUrl = new URL(url);
        String protocol = CoreUtilities.toLowerCase(resourceUrl.getProtocol());
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IOException("Resource pack URL must be HTTP or HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) resourceUrl.openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Denizen ResourcePackCommand");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IOException("Unexpected HTTP response " + response + " from resource pack URL.");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, length);
                }
            }
            return CoreUtilities.hexEncode(digest.digest());
        }
        finally {
            connection.disconnect();
        }
    }

    public static boolean checkOnline(PlayerTag player) {
        if (!player.isOnline()) {
            Debug.echoError("Invalid player '" + player.getName() + "' specified: must be online.");
            return false;
        }
        return true;
    }

    public static UUID parseUUID(String id) {
        try {
            return UUID.fromString(id);
        }
        catch (IllegalArgumentException ex) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static byte[] parseHash(String hash) {
        byte[] hashData = new byte[20];
        for (int i = 0; i < 20; i++) {
            hashData[i] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        }
        return hashData;
    }
}
