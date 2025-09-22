package org.blog.musket;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class Musket extends JavaPlugin implements Listener {

    private static final Material MUSKET_MAT = Material.DIAMOND_SWORD;
    private static final int MUSKET_CMD = 3;

    private static final double FIREBALL_SPEED = 3.8;
    private static final int RELOAD_TICKS = 20 * 4;
    private static final int RELOAD_SLOW_TICKS = 20 * 3;
    private static final int POST_MELEE_BLOCK_TICKS = 20 * 5;

    private static final double DMG_BODY_HP = 3.2 * 2.0;  // 6.4
    private static final double DMG_HEAD_HP = 3.5 * 2.0;  // 7.0

    private static final int HOLD_SLOW_AMPLIFIER = 0;           // SLOWNESS I
    private static final int HOLD_SLOW_DURATION_TICKS = 20 * 4;

    private static final int VICTIM_SLOW_TICKS = 20 * 3;
    private static final int VICTIM_UNLUCK_TICKS = 20 * 3;

    private static final Sound FIRE_SOUND = Sound.ENTITY_GENERIC_EXPLODE;
    private static final float FIRE_VOL = 0.7f;
    private static final float FIRE_PITCH = 1.2f;

    private final Map<UUID, Long> reloadUntil = new HashMap<>();
    private final Map<UUID, BukkitTask> reloadTasks = new HashMap<>();
    private final Set<UUID> reloading = new HashSet<>();
    private final Map<UUID, Long> meleeBlockUntil = new HashMap<>();

    private NamespacedKey KEY_MUSKET;

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        KEY_MUSKET = new NamespacedKey(this, "musket_bullet");
        Bukkit.getScheduler().runTaskTimer(this, this::tickHoldZoom, 20L, 20L);
    }

    @Override public void onDisable() {
        reloadTasks.values().forEach(t -> { if (t != null) t.cancel(); });
        reloadTasks.clear();
        reloading.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("플레이어만 사용 가능합니다."); return true; }
        if (!p.hasPermission("musket.give")) { p.sendMessage(ChatColor.RED + "권한이 없습니다."); return true; }
        p.getInventory().addItem(createMusketItem());
        p.sendMessage(ChatColor.AQUA + "머스킷을 지급했습니다!");
        return true;
    }

    private ItemStack createMusketItem() {
        ItemStack item = new ItemStack(MUSKET_MAT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("§b머스킷"));
        meta.setCustomModelData(MUSKET_CMD);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isMusket(ItemStack item) {
        if (item == null || item.getType() != MUSKET_MAT) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == MUSKET_CMD;
    }

    private void tickHoldZoom() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack main = p.getInventory().getItemInMainHand();
            if (isMusket(main)) {
                if (!reloading.contains(p.getUniqueId())) {
                    p.addPotionEffect(new PotionEffect(
                            PotionEffectType.SLOWNESS,  // ← 변경됨
                            HOLD_SLOW_DURATION_TICKS,
                            HOLD_SLOW_AMPLIFIER, true, false, false));
                }
            } else {
                PotionEffect eff = p.getPotionEffect(PotionEffectType.SLOWNESS);
                if (eff != null && eff.getAmplifier() == HOLD_SLOW_AMPLIFIER && eff.isAmbient() && !eff.hasParticles()) {
                    p.removePotionEffect(PotionEffectType.SLOWNESS);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isMusket(p.getInventory().getItemInMainHand())) return;

        long now = System.currentTimeMillis();
        if (meleeBlockUntil.getOrDefault(p.getUniqueId(), 0L) > now) { p.sendActionBar(Component.text("§c근접 공격 직후 5초 동안 사격할 수 없습니다.")); e.setCancelled(true); return; }
        if (reloading.contains(p.getUniqueId())) { p.sendActionBar(Component.text("§7장전 중...")); e.setCancelled(true); return; }
        if (reloadUntil.getOrDefault(p.getUniqueId(), 0L) > now) {
            long ms = reloadUntil.get(p.getUniqueId()) - now;
            p.sendActionBar(Component.text("§7장전 대기: " + (ms / 1000.0) + "s")); e.setCancelled(true); return;
        }

        shootFireball(p);
        startReload(p);
        e.setCancelled(true);
    }

    private void shootFireball(Player p) {
        Vector dir = p.getEyeLocation().getDirection().normalize();
        Fireball fb = p.launchProjectile(Fireball.class, dir.multiply(FIREBALL_SPEED));
        fb.setIsIncendiary(false);
        fb.setYield(0f);
        fb.setShooter(p);
        fb.getPersistentDataContainer().set(KEY_MUSKET, PersistentDataType.BYTE, (byte)1);

        p.getWorld().playSound(p.getLocation(), FIRE_SOUND, FIRE_VOL, FIRE_PITCH);

        // ✅ 파티클: 1.20.6에서 호환 잘 되는 호출
        var eye = p.getEyeLocation();
        p.getWorld().spawnParticle(
                Particle.SMOKE,          // ← SMOKE_NORMAL 말고 SMOKE
                eye.getX(), eye.getY(), eye.getZ(),
                6,                       // count
                0.05, 0.05, 0.05,        // offset x,y,z
                0.01                     // extra(speed)
        );
    }


    private void startReload(Player p) {
        UUID id = p.getUniqueId();
        reloading.add(id);

        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, RELOAD_SLOW_TICKS, 1, true, false, false)); // ← 변경됨

        BukkitTask prev = reloadTasks.remove(id);
        if (prev != null) prev.cancel();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> endReload(p, false, null), RELOAD_TICKS);
        reloadTasks.put(id, task);

        p.sendActionBar(Component.text("§b장전 중... (§f4.0s§b)"));
        reloadUntil.put(id, System.currentTimeMillis() + 4000);
    }

    private void endReload(Player p, boolean cancelled, String msg) {
        UUID id = p.getUniqueId();
        reloading.remove(id);
        BukkitTask t = reloadTasks.remove(id);
        if (t != null) t.cancel();

        if (cancelled) {
            PotionEffect eff = p.getPotionEffect(PotionEffectType.SLOWNESS); // ← 변경됨
            if (eff != null && eff.getAmplifier() == 1 && eff.isAmbient() && !eff.hasParticles()) {
                p.removePotionEffect(PotionEffectType.SLOWNESS); // ← 변경됨
            }
            if (msg != null) p.sendActionBar(Component.text("§c" + msg));
        } else {
            p.sendActionBar(Component.text("§a장전 완료!"));
        }
    }

    private boolean cancelReloadOnAction(Player p, String reason) {
        if (!reloading.contains(p.getUniqueId())) return false;
        endReload(p, true, "장전 취소: " + reason);
        return true;
    }

    @EventHandler public void onItemHeld(PlayerItemHeldEvent e) { cancelReloadOnAction(e.getPlayer(), "손을 바꿈"); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { cancelReloadOnAction(e.getPlayer(), "양손 전환"); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { cancelReloadOnAction(e.getPlayer(), "아이템 드롭"); }
    @EventHandler public void onInteractCancelReloadOther(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!isMusket(p.getInventory().getItemInMainHand())) return;
        Action a = e.getAction();
        boolean isShootTry = (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK);
        if (!isShootTry) cancelReloadOnAction(p, "다른 행동");
    }
    @EventHandler public void onToggleSprint(PlayerToggleSprintEvent e) { if (e.isSprinting()) cancelReloadOnAction(e.getPlayer(), "질주"); }
    @EventHandler public void onToggleSneak(PlayerToggleSneakEvent e) { cancelReloadOnAction(e.getPlayer(), "웅크리기/해제"); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { endReload(e.getPlayer(), true, "접속 종료"); meleeBlockUntil.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMelee(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isMusket(p.getInventory().getItemInMainHand())) return;

        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;

        e.setDamage(7.0);
        meleeBlockUntil.put(p.getUniqueId(), System.currentTimeMillis() + POST_MELEE_BLOCK_TICKS * 50L);
        p.sendActionBar(Component.text("§e근접 공격! §7(사격까지 5초 대기)"));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSweepSpread(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isMusket(p.getInventory().getItemInMainHand())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onProjectileDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Fireball fb)) return;
        Byte mark = fb.getPersistentDataContainer().get(KEY_MUSKET, PersistentDataType.BYTE);
        if (mark == null || mark != (byte)1) return;

        e.getEntity().setFireTicks(0);

        if (e.getEntity() instanceof LivingEntity le) {
            boolean headshot = fb.getLocation().getY() >= (le.getEyeLocation().getY() - 0.2);
            e.setDamage(headshot ? DMG_HEAD_HP : DMG_BODY_HP);

            le.addPotionEffect(new PotionEffect(PotionEffectType.UNLUCK, VICTIM_UNLUCK_TICKS, 0, true, true, true));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, VICTIM_SLOW_TICKS, 0, true, true, true)); // ← 변경됨

            Entity src = (Entity) fb.getShooter();
            if (src instanceof Player shooter) {
                shooter.sendActionBar(Component.text(
                        headshot ? "§b헤드샷! §f" + (DMG_HEAD_HP/2.0) + "❤ 고정"
                                : "§7바디샷 §f" + (DMG_BODY_HP/2.0) + "❤ 고정"));
                shooter.playSound(shooter, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, headshot ? 1.3f : 1.0f);
            }
        }
        fb.remove();
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (e.getEntity() instanceof Fireball fb) {
            Byte mark = fb.getPersistentDataContainer().get(KEY_MUSKET, PersistentDataType.BYTE);
            if (mark != null && mark == (byte)1) fb.remove();
        }
    }
}

