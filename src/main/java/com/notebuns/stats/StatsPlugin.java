package com.notebuns.stats;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Считает наигранное время и убийства враждебных мобов, включая модовых.
 *
 * Данные копятся в памяти и раз в минуту сбрасываются в MySQL: так падение
 * сервера стоит не больше минуты статистики, а база не получает запрос на
 * каждое убийство.
 *
 * Дельты собираются на основном потоке (Bukkit API нельзя трогать из
 * асинхронного), а запись в базу уходит в асинхронную задачу.
 */
public class StatsPlugin extends JavaPlugin implements Listener {

    private static final ZoneId TZ = ZoneId.of("Europe/Moscow");

    private String url, user, pass;
    private int flushSeconds;
    private java.util.Set<String> never = java.util.Set.of();
    private java.util.Set<String> force = java.util.Set.of();

    /** Когда игроку последний раз засчитали время. */
    private final Map<UUID, Long> lastCounted = new ConcurrentHashMap<>();
    /** Ники храним здесь, чтобы не дёргать Bukkit из асинхронного потока. */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** Незаписанные убийства. */
    private final Map<UUID, Long> pendingKills = new ConcurrentHashMap<>();
    /** Что именно убивали. По этому видно, не пропускаем ли модовых мобов. */
    private final Map<String, long[]> killTypes = new ConcurrentHashMap<>();
    /** Категория каждой убитой сущности по данным ядра, для проверки. */
    private final Map<String, String> killCats = new ConcurrentHashMap<>();
    /** Журнал убийств: только для разбора, на статистику не влияет. */
    private final java.util.Queue<LogRow> killLog = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private long lastPrune;
    private int logDays;

    // Защита от ферм. Ферму выдаёт не вид моба, а место и неподвижность:
    // она стоит на одной точке, а игрок при ней обычно висит афк.
    private int chunkCap;
    private long afkMillis;
    private double afkRadius;
    /** Убийства в чанке за текущий час: ключ мира и чанка -> {счёт, начало окна}. */
    private final Map<String, long[]> chunkKills = new ConcurrentHashMap<>();
    /** Где игрок стоял, когда последний раз заметно двигался, и когда это было. */
    private final Map<UUID, org.bukkit.Location> anchor = new ConcurrentHashMap<>();
    private final Map<UUID, Long> movedAt = new ConcurrentHashMap<>();

    /** Секунды, накопленные вышедшими игроками до ближайшего сброса. */
    private final Map<UUID, Long> pendingSeconds = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ старт

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String host = getConfig().getString("mysql.host", "");
        int port = getConfig().getInt("mysql.port", 3306);
        String db = getConfig().getString("mysql.database", "");
        user = getConfig().getString("mysql.username", "");
        pass = getConfig().getString("mysql.password", "");
        flushSeconds = Math.max(15, getConfig().getInt("flush-seconds", 60));
        never = new java.util.HashSet<>(getConfig().getStringList("never-monster"));
        force = new java.util.HashSet<>(getConfig().getStringList("count-as-monster"));
        logDays = getConfig().getInt("kill-log-days", 30);
        chunkCap = getConfig().getInt("anti-farm.chunk-kills-per-hour", 64);
        afkMillis = getConfig().getInt("anti-farm.afk-minutes", 5) * 60_000L;
        afkRadius = getConfig().getDouble("anti-farm.afk-radius", 3.0);
        url = "jdbc:mariadb://" + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=utf8&connectTimeout=10000&socketTimeout=20000";

        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            getLogger().severe("Драйвер MariaDB не найден, плагин выключен");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!createSchema()) {
            getLogger().severe("Не удалось подготовить базу, плагин выключен");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastCounted.put(p.getUniqueId(), now);
            names.put(p.getUniqueId(), p.getName());
        }

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::collectAndFlush,
                flushSeconds * 20L, flushSeconds * 20L);
        // положение опрашиваем раз в 10 секунд, а не через PlayerMoveEvent:
        // тот срабатывает на каждый шаг каждого игрока и стоит дорого
        Bukkit.getScheduler().runTaskTimer(this, this::sampleMovement, 200L, 200L);
        getLogger().info("Статистика включена: " + host + "/" + db
                + ", сброс раз в " + flushSeconds + " с");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        Snapshot snap = collect();
        if (snap != null) {
            write(snap);           // синхронно: сервер уже останавливается
        }
        getLogger().info("Статистика выключена, данные сброшены");
    }

    /** Отмечает, кто из игроков реально перемещается. */
    private void sampleMovement() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            org.bukkit.Location a = anchor.get(id);
            org.bukkit.Location cur = p.getLocation();
            if (a == null || !a.getWorld().equals(cur.getWorld())
                    || a.distanceSquared(cur) > afkRadius * afkRadius) {
                anchor.put(id, cur.clone());
                movedAt.put(id, now);
            }
        }
    }

    /** Стоит ли игрок без движения дольше порога. */
    private boolean afk(UUID id, long now) {
        Long t = movedAt.get(id);
        return t != null && now - t > afkMillis;
    }

    /** Не превышен ли потолок убийств для этого чанка в текущем часе. */
    private boolean chunkAllows(org.bukkit.Location l, long now) {
        if (chunkCap <= 0) {
            return true;
        }
        String key = l.getWorld().getName() + ":" + (l.getBlockX() >> 4) + ":" + (l.getBlockZ() >> 4);
        long[] v = chunkKills.computeIfAbsent(key, k -> new long[]{0, now});
        if (now - v[1] > 3600_000L) {
            v[0] = 0;
            v[1] = now;
        }
        if (v[0] >= chunkCap) {
            return false;
        }
        v[0]++;
        return true;
    }

    // ------------------------------------------------------------------ схема

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    private boolean createSchema() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS players ("
                + " uuid CHAR(36) NOT NULL,"
                + " name VARCHAR(16) NOT NULL,"
                + " total_seconds BIGINT NOT NULL DEFAULT 0,"
                + " total_kills BIGINT NOT NULL DEFAULT 0,"
                + " first_seen BIGINT NOT NULL,"
                + " last_seen BIGINT NOT NULL,"
                + " PRIMARY KEY (uuid),"
                + " KEY idx_seconds (total_seconds),"
                + " KEY idx_kills (total_kills)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            // суточные счётчики ведутся отдельно: топ дня это не срез общего топа
            "CREATE TABLE IF NOT EXISTS daily ("
                + " day DATE NOT NULL,"
                + " uuid CHAR(36) NOT NULL,"
                + " name VARCHAR(16) NOT NULL,"
                + " seconds BIGINT NOT NULL DEFAULT 0,"
                + " kills BIGINT NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (day, uuid),"
                + " KEY idx_day (day)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            // что засчитано как монстр, а что нет: так видны пропуски по модам
            "CREATE TABLE IF NOT EXISTS kill_types ("
                + " entity VARCHAR(160) NOT NULL,"
                + " counted TINYINT NOT NULL,"
                + " category VARCHAR(32) NOT NULL DEFAULT '?',"
                + " total BIGINT NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (entity)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            // журнал каждого убийства: нужен только для разбора спорных случаев,
            // чистится сам, в подсчёт топов не входит
            "CREATE TABLE IF NOT EXISTS kill_log ("
                + " id BIGINT NOT NULL AUTO_INCREMENT,"
                + " at BIGINT NOT NULL,"
                + " uuid CHAR(36) NOT NULL,"
                + " name VARCHAR(16) NOT NULL,"
                + " entity VARCHAR(160) NOT NULL,"
                + " category VARCHAR(32) NOT NULL,"
                + " counted TINYINT NOT NULL,"
                + " reason VARCHAR(24) NOT NULL DEFAULT '',"
                + " world VARCHAR(64) NOT NULL,"
                + " x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,"
                + " PRIMARY KEY (id), KEY idx_at (at), KEY idx_uuid (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            "CREATE TABLE IF NOT EXISTS meta ("
                + " k VARCHAR(64) NOT NULL, v TEXT,"
                + " PRIMARY KEY (k)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        };
        try (Connection c = open()) {
            for (String q : ddl) {
                try (PreparedStatement st = c.prepareStatement(q)) {
                    st.execute();
                }
            }
            // таблица могла быть создана до появления колонки категории
            // колонки, добавленные после первой версии. Наличие проверяем заранее:
            // просто поймать ошибку мало, драйвер сам пишет предупреждение в лог
            addColumn(c, "kill_types", "category", "VARCHAR(32) NOT NULL DEFAULT '?'");
            addColumn(c, "kill_log", "reason", "VARCHAR(24) NOT NULL DEFAULT ''");
            return true;
        } catch (SQLException e) {
            getLogger().severe("Схема: " + e.getMessage());
            return false;
        }
    }

    /** Добавляет колонку, если её ещё нет. */
    private void addColumn(Connection c, String table, String column, String type) {
        String q = "SELECT COUNT(*) FROM information_schema.COLUMNS"
                + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement st = c.prepareStatement(q)) {
            st.setString(1, table);
            st.setString(2, column);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        } catch (SQLException e) {
            return;                 // не смогли проверить, не трогаем
        }
        try (PreparedStatement st = c.prepareStatement(
                "ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + type)) {
            st.execute();
            getLogger().info("В таблицу " + table + " добавлена колонка " + column);
        } catch (SQLException e) {
            getLogger().warning("Колонка " + column + ": " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- события

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        lastCounted.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        names.put(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Long since = lastCounted.remove(id);
        if (since != null) {
            long sec = (System.currentTimeMillis() - since) / 1000L;
            if (sec > 0) {
                pendingSeconds.merge(id, sec, Long::sum);
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim instanceof Player) {
            return;
        }
        Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }
        String key = entityKey(victim);
        String cat = category(victim);
        boolean monster = isMonster(victim, key, cat);
        killTypes.computeIfAbsent(key, k -> new long[]{monster ? 1 : 0, 0})[1]++;
        killCats.put(key, cat);

        long now = System.currentTimeMillis();
        org.bukkit.Location l = victim.getLocation();
        String reason;
        if (!monster) {
            reason = "не монстр";
        } else if (afk(killer.getUniqueId(), now)) {
            reason = "афк";
        } else if (!chunkAllows(l, now)) {
            reason = "потолок чанка";
        } else {
            reason = "засчитано";
        }
        boolean counted = "засчитано".equals(reason);

        if (logDays > 0) {
            LogRow row = new LogRow();
            row.at = now;
            row.uuid = killer.getUniqueId().toString();
            row.name = killer.getName();
            row.entity = key;
            row.category = cat;
            row.counted = counted ? 1 : 0;
            row.reason = reason;
            row.world = l.getWorld().getName();
            row.x = l.getBlockX();
            row.y = l.getBlockY();
            row.z = l.getBlockZ();
            killLog.add(row);
        }
        if (counted) {
            pendingKills.merge(killer.getUniqueId(), 1L, Long::sum);
            names.put(killer.getUniqueId(), killer.getName());
        }
    }

    // ------------------------------------------------- распознавание монстров

    /**
     * Имя сущности вида {@code minecraft:zombie} или {@code alexsmobs:crocodile}.
     * У модовых мобов на гибридном ядре {@code getType()} может отдать UNKNOWN,
     * поэтому есть запасные пути.
     */
    private String entityKey(Entity e) {
        try {
            String k = e.getType().getKey().toString();
            if (!k.endsWith(":unknown")) {
                return k;
            }
        } catch (Throwable ignored) {
            // модовая сущность без записи в реестре Bukkit
        }
        try {
            Object handle = e.getClass().getMethod("getHandle").invoke(e);
            Object type = handle.getClass().getMethod("getType").invoke(handle);
            String s = String.valueOf(type);
            int a = s.indexOf('<'), b = s.lastIndexOf('>');
            if (a >= 0 && b > a) {
                s = s.substring(a + 1, b);
            }
            return s.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
    }

    /** Категория сущности в ядре: MONSTER, CREATURE, MISC... или "?" если не отдали. */
    private String category(Entity e) {
        try {
            Object handle = e.getClass().getMethod("getHandle").invoke(e);
            Object type = handle.getClass().getMethod("getType").invoke(handle);
            Object cat = type.getClass().getMethod("getCategory").invoke(type);
            return ((Enum<?>) cat).name();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * Враждебный ли моб.
     *
     * Главный признак: категория сущности в ядре. Моды объявляют своих мобов
     * через ту же {@code MobCategory}, что и ваниль, поэтому проверка ловит и
     * альексовских, и сумеречных, и всех остальных. Интерфейсы Bukkit идут
     * запасным вариантом: на гибридном ядре модовая сущность не всегда
     * реализует {@link Monster}.
     *
     * Отдельно закрыты пути для накрутки: всё, что можно разводить или
     * штамповать бесконечно, не засчитывается никогда, даже если мод объявил
     * своего моба враждебным.
     */
    private boolean isMonster(LivingEntity e, String key, String cat) {
        if (never.contains(key)) {
            return false;
        }
        if (force.contains(key)) {
            return true;
        }

        // защита от накрутки: разводимые животные, жители и големы не в счёт.
        // Ageable сюда не годится: у зомби тоже есть детёныши.
        if (e instanceof Animals || e instanceof AbstractVillager || e instanceof Golem) {
            return false;
        }
        if (e instanceof Tameable && !(e instanceof Monster)) {
            return false;
        }

        if ("MONSTER".equals(cat)) {
            return true;
        }
        if (!"?".equals(cat)) {
            // категория известна и это не монстр. Верим ей, кроме явных
            // исключений вроде слаймов, которых ядро относит к MONSTER не везде
            return e instanceof Monster || e instanceof Boss || e instanceof Slime;
        }
        // ядро категорию не отдало, поэтому смотрим на интерфейсы Bukkit
        return e instanceof Monster || e instanceof Boss || e instanceof Slime;
    }

    // --------------------------------------------------------------- запись

    private static final class Row {
        String name;
        long seconds;
        long kills;
    }

    private static final class Snapshot {
        final Map<UUID, Row> rows = new HashMap<>();
        final Map<String, long[]> types = new HashMap<>();
        final Map<String, String> cats = new HashMap<>();
        String day;
    }

    /** Снимает дельты на основном потоке. */
    private Snapshot collect() {
        long now = System.currentTimeMillis();
        Snapshot s = new Snapshot();
        s.day = LocalDate.now(TZ).toString();

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Long since = lastCounted.get(id);
            if (since == null) {
                lastCounted.put(id, now);
                continue;
            }
            long sec = (now - since) / 1000L;
            if (sec <= 0) {
                continue;
            }
            lastCounted.put(id, since + sec * 1000L);
            row(s, id, p.getName()).seconds += sec;
        }
        for (Map.Entry<UUID, Long> e : pendingSeconds.entrySet()) {
            row(s, e.getKey(), names.getOrDefault(e.getKey(), "?")).seconds += e.getValue();
        }
        pendingSeconds.clear();

        for (Map.Entry<UUID, Long> e : pendingKills.entrySet()) {
            row(s, e.getKey(), names.getOrDefault(e.getKey(), "?")).kills += e.getValue();
        }
        pendingKills.clear();

        long edge = System.currentTimeMillis() - 3600_000L;
        chunkKills.entrySet().removeIf(e -> e.getValue()[1] < edge);
        s.types.putAll(killTypes);
        s.cats.putAll(killCats);
        killTypes.clear();

        return s.rows.isEmpty() && s.types.isEmpty() ? null : s;
    }

    private Row row(Snapshot s, UUID id, String name) {
        Row r = s.rows.computeIfAbsent(id, k -> new Row());
        r.name = name;
        return r;
    }

    private void collectAndFlush() {
        Snapshot s = collect();
        if (s != null) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> write(s));
        }
    }

    private void write(Snapshot s) {
        String upPlayers = "INSERT INTO players"
                + " (uuid,name,total_seconds,total_kills,first_seen,last_seen)"
                + " VALUES (?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name),"
                + " total_seconds=total_seconds+VALUES(total_seconds),"
                + " total_kills=total_kills+VALUES(total_kills),"
                + " last_seen=VALUES(last_seen)";
        String upDaily = "INSERT INTO daily (day,uuid,name,seconds,kills) VALUES (?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name),"
                + " seconds=seconds+VALUES(seconds), kills=kills+VALUES(kills)";
        String upTypes = "INSERT INTO kill_types (entity,counted,category,total) VALUES (?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE counted=VALUES(counted),"
                + " category=VALUES(category), total=total+VALUES(total)";
        long now = System.currentTimeMillis();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement a = c.prepareStatement(upPlayers);
                 PreparedStatement b = c.prepareStatement(upDaily)) {
                for (Map.Entry<UUID, Row> e : s.rows.entrySet()) {
                    Row r = e.getValue();
                    a.setString(1, e.getKey().toString());
                    a.setString(2, r.name);
                    a.setLong(3, r.seconds);
                    a.setLong(4, r.kills);
                    a.setLong(5, now);
                    a.setLong(6, now);
                    a.addBatch();
                    b.setString(1, s.day);
                    b.setString(2, e.getKey().toString());
                    b.setString(3, r.name);
                    b.setLong(4, r.seconds);
                    b.setLong(5, r.kills);
                    b.addBatch();
                }
                a.executeBatch();
                b.executeBatch();
            }
            try (PreparedStatement t = c.prepareStatement(upTypes)) {
                for (Map.Entry<String, long[]> e : s.types.entrySet()) {
                    t.setString(1, e.getKey());
                    t.setInt(2, (int) e.getValue()[0]);
                    t.setString(3, s.cats.getOrDefault(e.getKey(), "?"));
                    t.setLong(4, e.getValue()[1]);
                    t.addBatch();
                }
                t.executeBatch();
            }
            writeLog(c);
            c.commit();
            prune(c);
        } catch (SQLException ex) {
            getLogger().warning("Сброс в базу не удался, данные вернутся в следующий раз: "
                    + ex.getMessage());
            // возвращаем убийства в очередь, время уже учтено сдвигом lastCounted
            for (Map.Entry<UUID, Row> e : s.rows.entrySet()) {
                if (e.getValue().kills > 0) {
                    pendingKills.merge(e.getKey(), e.getValue().kills, Long::sum);
                }
                if (e.getValue().seconds > 0) {
                    pendingSeconds.merge(e.getKey(), e.getValue().seconds, Long::sum);
                }
            }
            for (Map.Entry<String, long[]> e : s.types.entrySet()) {
                killTypes.merge(e.getKey(), e.getValue(),
                        (x, y) -> new long[]{y[0], x[1] + y[1]});
            }
        }
    }

    /** Одна строка журнала. Поля типизированы намеренно, см. writeLog. */
    private static final class LogRow {
        long at;
        String uuid, name, entity, category, reason, world;
        int counted, x, y, z;
    }

    /**
     * Сбрасывает журнал убийств. Отдельно от статистики: сбой тут её не портит.
     *
     * Параметры ставятся типизированными сеттерами, а не setObject: драйвер
     * MariaDB ищет кодек по классу объекта через ServiceLoader, а в асинхронной
     * задаче Bukkit контекстный загрузчик классов серверный, а не плагина.
     * Кодеки не находились, первый же long в боксе давал
     * «Type java.lang.Long not supported type», и журнал не писался вообще.
     */
    private void writeLog(Connection c) {
        if (killLog.isEmpty()) {
            return;
        }
        String q = "INSERT INTO kill_log (at,uuid,name,entity,category,counted,reason,world,x,y,z)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        List<LogRow> taken = new ArrayList<>();
        LogRow row;
        while (taken.size() < 5000 && (row = killLog.poll()) != null) {
            taken.add(row);
        }
        try (PreparedStatement st = c.prepareStatement(q)) {
            for (LogRow r : taken) {
                st.setLong(1, r.at);
                st.setString(2, r.uuid);
                st.setString(3, r.name);
                st.setString(4, r.entity);
                st.setString(5, r.category);
                st.setInt(6, r.counted);
                st.setString(7, r.reason);
                st.setString(8, r.world);
                st.setInt(9, r.x);
                st.setInt(10, r.y);
                st.setInt(11, r.z);
                st.addBatch();
            }
            st.executeBatch();
        } catch (SQLException e) {
            // строки уже вынуты из очереди, возвращаем их, иначе они пропадут;
            // потолок держим, чтобы долгая недоступность базы не съела память
            if (killLog.size() < 20000) {
                killLog.addAll(taken);
            }
            getLogger().warning("Журнал убийств не записан: " + e.getMessage());
        }
    }

    /** Раз в час убирает из журнала записи старше настроенного срока. */
    private void prune(Connection c) {
        if (logDays <= 0 || System.currentTimeMillis() - lastPrune < 3600_000L) {
            return;
        }
        lastPrune = System.currentTimeMillis();
        long edge = System.currentTimeMillis() - logDays * 86400_000L;
        try (PreparedStatement st = c.prepareStatement("DELETE FROM kill_log WHERE at < ?")) {
            st.setLong(1, edge);
            int n = st.executeUpdate();
            if (n > 0) {
                getLogger().info("Журнал убийств: удалено старых записей " + n);
            }
        } catch (SQLException e) {
            getLogger().warning("Чистка журнала: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- команда

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> sendTop(sender));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("flush")) {
            if (!sender.hasPermission("notebunsstats.admin")) {
                sender.sendMessage(ChatColor.RED + "Нет прав.");
                return true;
            }
            collectAndFlush();
            sender.sendMessage(ChatColor.GREEN + "Сброшено в базу.");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Использование: /" + label + " top");
            return true;
        }
        Player p = (Player) sender;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> sendSelf(p));
        return true;
    }

    private void sendSelf(Player p) {
        String q = "SELECT total_seconds, total_kills,"
                + " (SELECT COUNT(*)+1 FROM players x WHERE x.total_seconds>p.total_seconds) rt,"
                + " (SELECT COUNT(*)+1 FROM players x WHERE x.total_kills>p.total_kills) rk"
                + " FROM players p WHERE uuid=?";
        try (Connection c = open(); PreparedStatement st = c.prepareStatement(q)) {
            st.setString(1, p.getUniqueId().toString());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    p.sendMessage(ChatColor.GRAY + "Статистика пока пуста, зайди попозже.");
                    return;
                }
                p.sendMessage(ChatColor.GOLD + "── Твоя статистика ──");
                p.sendMessage(ChatColor.GREEN + "В игре: " + ChatColor.WHITE
                        + human(rs.getLong(1)) + ChatColor.GRAY + "  (место " + rs.getInt(3) + ")");
                p.sendMessage(ChatColor.RED + "Монстров убито: " + ChatColor.WHITE
                        + rs.getLong(2) + ChatColor.GRAY + "  (место " + rs.getInt(4) + ")");
            }
        } catch (SQLException e) {
            p.sendMessage(ChatColor.RED + "База недоступна, попробуй позже.");
        }
    }

    private void sendTop(CommandSender to) {
        try (Connection c = open()) {
            List<String> out = new ArrayList<>();
            out.add(ChatColor.GOLD + "── Топ-5 по времени ──");
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT name,total_seconds FROM players ORDER BY total_seconds DESC LIMIT 5");
                 ResultSet rs = st.executeQuery()) {
                int i = 1;
                while (rs.next()) {
                    out.add(ChatColor.GREEN + " " + i++ + ". " + ChatColor.WHITE
                            + rs.getString(1) + ChatColor.GRAY + ": " + human(rs.getLong(2)));
                }
            }
            out.add(ChatColor.GOLD + "── Топ-5 по монстрам ──");
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT name,total_kills FROM players ORDER BY total_kills DESC LIMIT 5");
                 ResultSet rs = st.executeQuery()) {
                int i = 1;
                while (rs.next()) {
                    out.add(ChatColor.RED + " " + i++ + ". " + ChatColor.WHITE
                            + rs.getString(1) + ChatColor.GRAY + ": " + rs.getLong(2));
                }
            }
            for (String l : out) {
                to.sendMessage(l);
            }
        } catch (SQLException e) {
            to.sendMessage(ChatColor.RED + "База недоступна, попробуй позже.");
        }
    }

    private static String human(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60;
        if (d > 0) {
            return d + " д " + h + " ч";
        }
        if (h > 0) {
            return h + " ч " + m + " мин";
        }
        return m + " мин";
    }
}
