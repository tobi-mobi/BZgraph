package dev.bzgraph.mc;

import dev.bzgraph.BzGraphClient;
import dev.bzgraph.core.purse.PurseParser;
import dev.bzgraph.core.purse.PurseRecorder;
import dev.bzgraph.core.purse.PurseStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Records the purse shown in SkyBlock's sidebar, once a second, per account and profile. Only reads what's
 * already on screen; nothing is sent anywhere.
 */
public final class PurseTracker {
	private static final int READ_EVERY_TICKS = 20;
	private static final int PROFILE_EVERY_TICKS = 20 * 10;
	private static final int SAVE_EVERY_TICKS = 20 * 30;

	private final PurseRecorder recorder;
	private final Executor io;
	private ClientLevel lastLevel;
	private int ticks;

	public PurseTracker(Path configDir, Executor io) {
		this.recorder = new PurseRecorder(new PurseStore(configDir.resolve("purse.json")));
		this.io = io;
	}

	public PurseStore store() {
		return recorder.store();
	}

	public void start() {
		io.execute(() -> {
			store().load();
			store().compact(System.currentTimeMillis());
		});
	}

	public void stop() {
		save();
	}

	private void save() {
		try {
			store().saveIfDirty();
		} catch (Exception e) {
			BzGraphClient.LOGGER.warn("Could not save purse history: {}", e.toString());
		}
	}

	public void onMessage(Component message) {
		String p = PurseParser.profileFromChat(message.getString());
		if (p != null) recorder.onProfile(TradeTracker.account(), p);
	}

	public void tick(Minecraft mc, boolean active) {
		ticks++;
		if (ticks % SAVE_EVERY_TICKS == 0) io.execute(this::save);
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			recorder.onWorldChange(Instant.now());
		}
		if (!active || mc.level == null || !BzGraphClient.get().config().trackPurse) return;
		if (ticks % PROFILE_EVERY_TICKS == 0) {
			String p = PurseParser.profileFromTab(tabEntries(mc));
			if (p != null) recorder.onProfile(TradeTracker.account(), p);
		}
		if (ticks % READ_EVERY_TICKS == 0) {
			recorder.onReading(TradeTracker.account(), PurseParser.purse(sidebar(mc.level.getScoreboard())), Instant.now());
		}
	}

	static List<String> sidebar(Scoreboard sb) {
		Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (obj == null) return List.of();
		List<String> lines = new ArrayList<>();
		for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
			if (e.isHidden()) continue;
			PlayerTeam team = sb.getPlayersTeam(e.owner());
			Component name = e.ownerName();
			lines.add(team == null ? name.getString() : PlayerTeam.formatNameForTeam(team, name).getString());
		}
		return lines;
	}

	private static List<String> tabEntries(Minecraft mc) {
		ClientPacketListener conn = mc.getConnection();
		if (conn == null) return List.of();
		List<String> out = new ArrayList<>();
		for (PlayerInfo info : conn.getListedOnlinePlayers()) {
			Component c = info.getTabListDisplayName();
			if (c != null) out.add(c.getString());
		}
		return out;
	}
}
