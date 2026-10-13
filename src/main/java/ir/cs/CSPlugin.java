package ir.cs;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CSPlugin extends JavaPlugin implements Listener, TabExecutor {

    // ====== تنظیمات مینی‌هک (اگه خواستی عوض کن) ======
    private static final int NEEDED_SUCCESS = 2;      // چند بار موفق بشه تا یک CS کم بشه
    private static final int BASE_LENGTH = 5;          // طول ترتیب مرحله اول (مرحله بعد +1)
    private static final int FLASH_TICKS = 7;          // مدت رنگی بودن هر بلاک
    private static final int GAP_TICKS = 4;            // فاصله بین دو فلش

    private static final Material WHITE = Material.WHITE_CONCRETE;
    private static final Material[] COLORS = {
            Material.LIME_CONCRETE, Material.YELLOW_CONCRETE,
            Material.BLUE_CONCRETE, Material.RED_CONCRETE
    };
    private static final String[] COLOR_NAMES = {"§aسبز", "§eزرد", "§9آبی", "§cقرمز"};

    // ====== داده‌ها ======
    private static class Jail {
        int remaining;
        String reason;
        Location returnLoc;
    }

    private static class Session {
        List<Integer> seq = new ArrayList<>();
        int input = 0;
        int successes = 0;
        boolean showing = true;
        List<BukkitTask> tasks = new ArrayList<>();
        Inventory inv; // فقط در حالت پنل
    }

    private final Map<UUID, Jail> jailed = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> bypassTp = new HashSet<>();
    private final Random rnd = new Random();

    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("cs").setExecutor(this);
        getCommand("cs").setTabCompleter(this);
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
    }

    @Override
    public void onDisable() {
        for (Session s : sessions.values()) s.tasks.forEach(BukkitTask::cancel);
        saveData();
    }

    // ====== ذخیره/لود ======
    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        if (!y.isConfigurationSection("jailed")) return;
        for (String k : y.getConfigurationSection("jailed").getKeys(false)) {
            Jail j = new Jail();
            j.remaining = y.getInt("jailed." + k + ".remaining");
            j.reason = y.getString("jailed." + k + ".reason", "");
            j.returnLoc = y.getLocation("jailed." + k + ".return");
            jailed.put(UUID.fromString(k), j);
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Jail> e : jailed.entrySet()) {
            String p = "jailed." + e.getKey();
            y.set(p + ".remaining", e.getValue().remaining);
            y.set(p + ".reason", e.getValue().reason);
            y.set(p + ".return", e.getValue().returnLoc);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ====== کمک‌ها ======
    // حالت مینی‌هک: gui = پنل (پیش‌فرض) ، world = بلاک‌های واقعی
    private boolean isGui() { return !"world".equalsIgnoreCase(getConfig().getString("mode", "gui")); }
    private static final int[] SLOTS = {10, 12, 14, 16};

    private ItemStack panelItem(Material m, int i) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(m == WHITE ? "§7؟" : COLOR_NAMES[i]);
        it.setItemMeta(meta);
        return it;
    }

    private Location spawn() { return getConfig().getLocation("spawn"); }
    private Location hackBlock(int i) { return getConfig().getLocation("hack." + i); }

    private boolean inRegion(Location l) {
        Location a = getConfig().getLocation("pos1"), b = getConfig().getLocation("pos2");
        if (a == null || b == null || l == null || l.getWorld() == null || !l.getWorld().equals(a.getWorld()))
            return false;
        return l.getX() >= Math.min(a.getX(), b.getX()) && l.getX() <= Math.max(a.getX(), b.getX()) + 1
                && l.getY() >= Math.min(a.getY(), b.getY()) && l.getY() <= Math.max(a.getY(), b.getY()) + 3
                && l.getZ() >= Math.min(a.getZ(), b.getZ()) && l.getZ() <= Math.max(a.getZ(), b.getZ()) + 1;
    }

    private boolean isWorkBlock(Block b) {
        List<Location> list = (List<Location>) getConfig().getList("work", new ArrayList<>());
        for (Location l : list) if (sameBlock(l, b.getLocation())) return true;
        return false;
    }

    private int hackIndex(Block b) {
        for (int i = 0; i < 4; i++) {
            Location l = hackBlock(i);
            if (l != null && sameBlock(l, b.getLocation())) return i;
        }
        return -1;
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private void teleportSafe(Player p, Location l) {
        bypassTp.add(p.getUniqueId());
        p.teleport(l);
        bypassTp.remove(p.getUniqueId());
    }

    private void msg(CommandSender s, String m) { s.sendMessage("§8[§6CS§8] §f" + m); }

    // ====== دستورها ======
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.isOp()) { msg(sender, "§cفقط OP ها می‌تونن."); return true; }
        if (a.length == 0) { usage(sender); return true; }

        String sub = a[0].toLowerCase();

        // ----- ستاپ -----
        switch (sub) {
            case "setspawn", "pos1", "pos2", "addwork", "clearwork", "sethack" -> {
                if (!(sender instanceof Player p)) { msg(sender, "§cفقط داخل بازی."); return true; }
                setup(p, sub, a);
                return true;
            }
            case "release" -> {
                if (a.length < 2) { usage(sender); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null || !jailed.containsKey(t.getUniqueId())) { msg(sender, "§cاین بازیکن CS نیست."); return true; }
                release(t, true);
                return true;
            }
            case "list" -> {
                if (jailed.isEmpty()) { msg(sender, "کسی CS نیست."); return true; }
                for (Map.Entry<UUID, Jail> e : jailed.entrySet())
                    msg(sender, Bukkit.getOfflinePlayer(e.getKey()).getName() + " §7- §e" + e.getValue().remaining + " CS §7- " + e.getValue().reason);
                return true;
            }
        }

        // ----- /cs <player> <amount> <reason> -----
        if (a.length < 3) { usage(sender); return true; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { msg(sender, "§cبازیکن آنلاین نیست."); return true; }
        int amount;
        try { amount = Integer.parseInt(a[1]); } catch (NumberFormatException e) { msg(sender, "§cتعداد باید عدد باشه."); return true; }
        if (amount < 1) { msg(sender, "§cتعداد باید حداقل ۱ باشه."); return true; }
        String reason = String.join(" ", Arrays.copyOfRange(a, 2, a.length));

        if (spawn() == null || getConfig().getLocation("pos1") == null || getConfig().getLocation("pos2") == null) {
            msg(sender, "§cاول مکان CS رو ست کن: /cs setspawn, /cs pos1, /cs pos2"); return true;
        }
        if (!isGui()) for (int i = 0; i < 4; i++) if (hackBlock(i) == null) { msg(sender, "§cبلاک‌های هک ست نشدن: /cs sethack 1..4"); return true; }

        Jail j = jailed.get(t.getUniqueId());
        if (j != null) {
            j.remaining += amount;
            j.reason = reason;
        } else {
            j = new Jail();
            j.remaining = amount;
            j.reason = reason;
            j.returnLoc = t.getLocation();
            jailed.put(t.getUniqueId(), j);
            teleportSafe(t, spawn());
        }
        saveData();

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage("§8§m                                        ");
        Bukkit.broadcastMessage("§c§l⚖ خدمات اجتماعی (CS)");
        Bukkit.broadcastMessage("§fبازیکن §e" + t.getName() + " §fبه §c" + amount + " CS §fمحکوم شد");
        Bukkit.broadcastMessage("§fدلیل: §7" + reason);
        Bukkit.broadcastMessage("§8§m                                        ");
        Bukkit.broadcastMessage("");

        t.sendMessage("§eروی بلاک‌های کار کلیک راست بزن و مینی‌هک رو رد کن. §7(باقی‌مونده: §c" + j.remaining + "§7)");
        return true;
    }

    private void usage(CommandSender s) {
        msg(s, "§e/cs <player> <amount> <reason>");
        msg(s, "§7/cs release <player> | /cs list");
        msg(s, "§7ستاپ: /cs setspawn | pos1 | pos2 | addwork | clearwork | sethack <1-4>");
    }

    private void setup(Player p, String sub, String[] a) {
        switch (sub) {
            case "setspawn" -> { getConfig().set("spawn", p.getLocation()); saveConfig(); msg(p, "نقطه‌ی تلپورت CS ست شد."); }
            case "pos1" -> { getConfig().set("pos1", p.getLocation().getBlock().getLocation()); saveConfig(); msg(p, "گوشه‌ی ۱ منطقه ست شد."); }
            case "pos2" -> { getConfig().set("pos2", p.getLocation().getBlock().getLocation()); saveConfig(); msg(p, "گوشه‌ی ۲ منطقه ست شد."); }
            case "clearwork" -> { getConfig().set("work", new ArrayList<Location>()); saveConfig(); msg(p, "همه‌ی بلاک‌های کار پاک شد."); }
            case "addwork", "sethack" -> {
                Block b = p.getTargetBlockExact(6);
                if (b == null) { msg(p, "§cبه یه بلاک نگاه کن."); return; }
                if (sub.equals("addwork")) {
                    List<Location> list = new ArrayList<>((List<Location>) getConfig().getList("work", new ArrayList<>()));
                    list.add(b.getLocation());
                    getConfig().set("work", list);
                    msg(p, "بلاک کار اضافه شد (" + list.size() + ").");
                } else {
                    int n;
                    try { n = Integer.parseInt(a[1]); } catch (Exception e) { n = 0; }
                    if (n < 1 || n > 4) { msg(p, "§c/cs sethack <1-4>"); return; }
                    getConfig().set("hack." + (n - 1), b.getLocation());
                    msg(p, "بلاک هک شماره‌ی " + n + " ست شد.");
                }
                saveConfig();
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.isOp()) return List.of();
        if (a.length == 1) {
            List<String> r = new ArrayList<>(List.of("setspawn", "pos1", "pos2", "addwork", "clearwork", "sethack", "release", "list"));
            Bukkit.getOnlinePlayers().forEach(p -> r.add(p.getName()));
            return r;
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("sethack")) return List.of("1", "2", "3", "4");
        if (a.length == 2 && a[0].equalsIgnoreCase("release")) return jailed.keySet().stream()
                .map(u -> Bukkit.getOfflinePlayer(u).getName()).filter(Objects::nonNull).toList();
        return List.of();
    }

    // ====== مینی‌هک ======
    private void startHack(Player p) {
        if (sessions.containsKey(p.getUniqueId())) return;
        Session s = new Session();
        sessions.put(p.getUniqueId(), s);
        if (isGui()) {
            s.inv = Bukkit.createInventory(null, 27, "§c§lهک");
            ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
            for (int i = 0; i < 27; i++) s.inv.setItem(i, filler);
            p.openInventory(s.inv);
        }
        for (int i = 0; i < 4; i++) show(p, i, WHITE);
        p.sendTitle("§c§lهک", "§7ترتیب رنگ‌ها رو یادت بمونه...", 5, 30, 5);
        s.tasks.add(Bukkit.getScheduler().runTaskLater(this, () -> newRound(p), 40L));
    }

    private void show(Player p, int i, Material m) {
        if (isGui()) {
            Session s = sessions.get(p.getUniqueId());
            if (s != null && s.inv != null) s.inv.setItem(SLOTS[i], panelItem(m, i));
            return;
        }
        Location l = hackBlock(i);
        if (l != null) p.sendBlockChange(l, m.createBlockData());
    }

    private void newRound(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !p.isOnline()) return;
        s.seq.clear();
        s.input = 0;
        s.showing = true;
        int len = BASE_LENGTH + s.successes;
        for (int i = 0; i < len; i++) s.seq.add(rnd.nextInt(4));

        int step = FLASH_TICKS + GAP_TICKS;
        for (int i = 0; i < len; i++) {
            final int block = s.seq.get(i);
            s.tasks.add(Bukkit.getScheduler().runTaskLater(this, () -> {
                show(p, block, COLORS[block]);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 0.6f + block * 0.3f);
            }, (long) i * step));
            s.tasks.add(Bukkit.getScheduler().runTaskLater(this, () -> show(p, block, WHITE), (long) i * step + FLASH_TICKS));
        }
        s.tasks.add(Bukkit.getScheduler().runTaskLater(this, () -> {
            s.showing = false;
            p.sendActionBar(Component.text("§aنوبت توئه! (مرحله " + (s.successes + 1) + "/" + NEEDED_SUCCESS + ")"));
        }, (long) len * step));
    }

    private void handleHackClick(Player p, int idx) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.showing) return;

        show(p, idx, COLORS[idx]);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 0.6f + idx * 0.3f);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (sessions.containsKey(p.getUniqueId())) show(p, idx, WHITE); }, 4L);

        if (s.seq.get(s.input) != idx) {
            // اشتباه شد
            s.showing = true;
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            p.sendTitle("§c§lاشتباه!", "§7دوباره از اول...", 0, 25, 5);
            s.tasks.add(Bukkit.getScheduler().runTaskLater(this, () -> newRound(p), 40L));
            return;
        }

        s.input++;
        if (s.input < s.seq.size()) return;

        // این مرحله درست بود
        s.showing = true;
        s.successes++;
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);

        if (s.successes < NEEDED_SUCCESS) {
            p.sendTitle("§a§lدرسته!", "§7مرحله‌ی بعد سخت‌تره", 0, 25, 5);
            s.tasks.add(Bukkit.getScheduler().runTaskLater(this, () -> newRound(p), 40L));
            return;
        }

        // یک CS تموم شد
        endSession(p);
        Jail j = jailed.get(p.getUniqueId());
        if (j == null) return;
        j.remaining--;
        saveData();
        if (j.remaining <= 0) {
            release(p, false);
        } else {
            p.sendTitle("§a§l1 CS تموم شد", "§7باقی‌مونده: §c" + j.remaining, 5, 40, 10);
        }
    }

    private void endSession(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) {
            s.tasks.forEach(BukkitTask::cancel);
            if (s.inv != null) p.closeInventory();
        }
        if (!isGui()) {
            for (int i = 0; i < 4; i++) {
                Location l = hackBlock(i);
                if (l != null) p.sendBlockChange(l, l.getBlock().getBlockData());
            }
        }
    }

    private void release(Player p, boolean forced) {
        endSession(p);
        Jail j = jailed.remove(p.getUniqueId());
        saveData();
        if (j == null) return;
        if (j.returnLoc != null) teleportSafe(p, j.returnLoc);
        p.sendTitle("§a§lآزاد شدی", forced ? "§7توسط ادمین آزاد شدی" : "§7CS هات تموم شد", 5, 40, 10);
        Bukkit.broadcastMessage("§8[§6CS§8] §e" + p.getName() + " §fCS هاش رو تموم کرد و آزاد شد.");
    }

    // ====== ایونت‌ها ======
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (e.getHand() != null && e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!jailed.containsKey(p.getUniqueId())) return;

        e.setCancelled(true);
        Block b = e.getClickedBlock();

        if (sessions.containsKey(p.getUniqueId())) {
            int idx = hackIndex(b);
            if (idx >= 0) handleHackClick(p, idx);
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK && isWorkBlock(b)) {
            startHack(p);
        }
    }

    @EventHandler
    public void onPanelClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.inv == null || !e.getView().getTopInventory().equals(s.inv)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(s.inv)) return;
        for (int i = 0; i < 4; i++) if (SLOTS[i] == e.getSlot()) handleHackClick(p, i);
    }

    @EventHandler
    public void onPanelClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.inv != null && e.getInventory().equals(s.inv)) endSession(p);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!jailed.containsKey(p.getUniqueId()) || e.getTo() == null) return;
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        if (!inRegion(e.getTo())) {
            p.sendActionBar(Component.text("§cنمی‌تونی از منطقه‌ی CS خارج بشی!"));
            teleportSafe(p, spawn());
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!jailed.containsKey(p.getUniqueId()) || bypassTp.contains(p.getUniqueId())) return;
        if (!inRegion(e.getTo())) e.setCancelled(true);
    }

    @EventHandler
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (jailed.containsKey(p.getUniqueId()) && !p.isOp()) {
            e.setCancelled(true);
            p.sendMessage("§cتوی CS نمی‌تونی از دستور استفاده کنی.");
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) { protect(e.getPlayer(), e); }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) { protect(e.getPlayer(), e); }

    private void protect(Player p, Cancellable e) {
        if (jailed.containsKey(p.getUniqueId()) || (!p.isOp() && inRegion(p.getLocation()))) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && jailed.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (jailed.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (jailed.containsKey(e.getPlayer().getUniqueId()) && spawn() != null) e.setRespawnLocation(spawn());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (jailed.containsKey(p.getUniqueId()) && spawn() != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> teleportSafe(p, spawn()), 5L);
            p.sendMessage("§cتو هنوز §e" + jailed.get(p.getUniqueId()).remaining + " CS §cباقی داری.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) s.tasks.forEach(BukkitTask::cancel);
    }
}
