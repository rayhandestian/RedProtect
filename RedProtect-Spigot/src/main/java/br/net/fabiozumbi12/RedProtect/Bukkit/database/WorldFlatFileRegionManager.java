/*
 * Copyright (c) 2012-2024 - @FabioZumbi12
 * Last Modified: 26/11/2024 17:51
 *
 * This class is provided 'as-is', without any express or implied warranty. In no event will the authors be held liable for any
 *  damages arising from the use of this class.
 *
 * Permission is granted to anyone to use this class for any purpose, including commercial plugins, and to alter it and
 * redistribute it freely, subject to the following restrictions:
 * 1 - The origin of this class must not be misrepresented; you must not claim that you wrote the original software. If you
 * use this class in other plugins, an acknowledgment in the plugin documentation would be appreciated but is not required.
 * 2 - Altered source versions must be plainly marked as such, and must not be misrepresented as being the original class.
 * 3 - This notice may not be removed or altered from any source distribution.
 *
 * Esta classe é fornecida "como está", sem qualquer garantia expressa ou implícita. Em nenhum caso os autores serão
 * responsabilizados por quaisquer danos decorrentes do uso desta classe.
 *
 * É concedida permissão a qualquer pessoa para usar esta classe para qualquer finalidade, incluindo plugins pagos, e para
 * alterá-lo e redistribuí-lo livremente, sujeito às seguintes restrições:
 * 1 - A origem desta classe não deve ser deturpada; você não deve afirmar que escreveu a classe original. Se você usar esta
 *  classe em um plugin, uma confirmação de autoria na documentação do plugin será apreciada, mas não é necessária.
 * 2 - Versões de origem alteradas devem ser claramente marcadas como tal e não devem ser deturpadas como sendo a
 * classe original.
 * 3 - Este aviso não pode ser removido ou alterado de qualquer distribuição de origem.
 */

package br.net.fabiozumbi12.RedProtect.Bukkit.database;

import br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect;
import br.net.fabiozumbi12.RedProtect.Bukkit.Region;
import br.net.fabiozumbi12.RedProtect.Core.helpers.CoreUtil;
import br.net.fabiozumbi12.RedProtect.Core.helpers.LogLevel;
import br.net.fabiozumbi12.RedProtect.Core.region.PlayerRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class WorldFlatFileRegionManager implements WorldRegionManager {

    private final HashMap<String, Region> regions;
    // Spatial index: chunk -> regions whose horizontal MBR touches that chunk.
    // Point lookups (getRegions/getTopRegion/getLowRegion/getGroupRegion) run on every
    // PlayerMoveEvent, so scanning every region in the world does not scale: a world with
    // a few thousand regions spends most of its move-handling time walking this map.
    // Regions are identity-compared (Region declares no equals/hashCode), and their
    // horizontal bounds are immutable once constructed - redefine builds a new Region and
    // goes through remove()/add() - so an index keyed on those bounds cannot go stale as
    // long as it is maintained in add(), remove() and clearRegions().
    private final Map<Long, Set<Region>> chunkIndex;
    // A region covering more chunks than this is not indexed per chunk - it is kept here
    // and scanned on every lookup instead. Without this cap a single huge region (an admin
    // region over a whole world, say) would try to register millions of chunk entries at
    // load time. Such regions are rare, so this list stays short.
    private final Set<Region> unindexedLargeRegions;
    private static final long MAX_INDEXED_CHUNKS = 1024L;
    private final String world;

    public WorldFlatFileRegionManager(String world) {
        super();
        this.regions = new HashMap<>();
        this.chunkIndex = new HashMap<>();
        this.unindexedLargeRegions = new HashSet<>();
        this.world = world;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    private static Region loadRegion(YamlConfiguration fileDB, String rname, String world) {
        Region newr = null;
        try {
            if (fileDB.getString(rname + ".name") == null) {
                fileDB.set(rname + ".name", rname);
            }
            int maxX = fileDB.getInt(rname + ".maxX");
            int maxZ = fileDB.getInt(rname + ".maxZ");
            int minX = fileDB.getInt(rname + ".minX");
            int minZ = fileDB.getInt(rname + ".minZ");
            int maxY = fileDB.getInt(rname + ".maxY", Bukkit.getWorld(world).getMaxHeight());
            int minY = fileDB.getInt(rname + ".minY", Bukkit.getWorld(world).getMaxHeight());

            if (minY == 0 && RedProtect.get().getConfigManager().configRoot().region_settings.convert_zeros_y)
                minY = Bukkit.getWorld(world).getMinHeight();

            String name = fileDB.getString(rname + ".name");
            String serverName = RedProtect.get().getConfigManager().configRoot().region_settings.default_leader;
            Set<PlayerRegion> leaders = new HashSet<>(fileDB.getStringList(rname + ".leaders")).stream().filter(s -> s.split("@").length == 1 || (s.split("@").length == 2 && !s.split("@")[1].isEmpty())).map(s -> {
                String[] pi = s.split("@");
                String[] p = new String[]{pi[0], pi.length == 2 ? pi[1] : pi[0]};
                if (!p[0].equalsIgnoreCase(serverName) && !p[1].equalsIgnoreCase(serverName)) {
                    if (RedProtect.get().getUtil().isUUIDs(p[1])) {
                        String before = p[1];
                        p[1] = RedProtect.get().getUtil().UUIDtoPlayer(p[1]) == null ? p[1] : RedProtect.get().getUtil().UUIDtoPlayer(p[1]).toLowerCase();
                        RedProtect.get().logger.success("Updated region " + rname + ", player &6" + before + " &ato &6" + p[1]);
                    }
                }
                return new PlayerRegion(p[0], p[1]);
            }).collect(Collectors.toSet());

            Set<PlayerRegion> admins = new HashSet<>(fileDB.getStringList(rname + ".admins")).stream().filter(s -> s.split("@").length == 1 || (s.split("@").length == 2 && !s.split("@")[1].isEmpty())).map(s -> {
                String[] pi = s.split("@");
                String[] p = new String[]{pi[0], pi.length == 2 ? pi[1] : pi[0]};
                if (!p[0].equalsIgnoreCase(serverName) && !p[1].equalsIgnoreCase(serverName)) {
                    if (RedProtect.get().getUtil().isUUIDs(p[1])) {
                        String before = p[1];
                        p[1] = RedProtect.get().getUtil().UUIDtoPlayer(p[1]) == null ? p[1] : RedProtect.get().getUtil().UUIDtoPlayer(p[1]).toLowerCase();
                        RedProtect.get().logger.success("Updated region " + rname + ", player &6" + before + " &ato &6" + p[1]);
                    }
                }
                return new PlayerRegion(p[0], p[1]);
            }).collect(Collectors.toSet());

            Set<PlayerRegion> members = new HashSet<>(fileDB.getStringList(rname + ".members")).stream().filter(s -> s.split("@").length == 1 || (s.split("@").length == 2 && !s.split("@")[1].isEmpty())).map(s -> {
                String[] pi = s.split("@");
                String[] p = new String[]{pi[0], pi.length == 2 ? pi[1] : pi[0]};
                if (!p[0].equalsIgnoreCase(serverName) && !p[1].equalsIgnoreCase(serverName)) {
                    if (RedProtect.get().getUtil().isUUIDs(p[1])) {
                        String before = p[1];
                        p[1] = RedProtect.get().getUtil().UUIDtoPlayer(p[1]) == null ? p[1] : RedProtect.get().getUtil().UUIDtoPlayer(p[1]).toLowerCase();
                        RedProtect.get().logger.success("Updated region " + rname + ", player &6" + before + " &ato &6" + p[1]);
                    }
                }
                return new PlayerRegion(p[0], p[1]);
            }).collect(Collectors.toSet());

            String welcome = fileDB.getString(rname + ".welcome", "");
            int prior = fileDB.getInt(rname + ".priority", 0);
            String date = fileDB.getString(rname + ".lastvisit", "");
            double value = fileDB.getDouble(rname + ".value", 0);
            boolean candel = fileDB.getBoolean(rname + ".candelete", true);
            boolean canPurge = fileDB.getBoolean(rname + ".canpurge", true);

            Location tppoint = null;
            if (!fileDB.getString(rname + ".tppoint", "").isEmpty()) {
                String[] tpstring = fileDB.getString(rname + ".tppoint").split(",");
                tppoint = new Location(Bukkit.getWorld(world), Double.parseDouble(tpstring[0]), Double.parseDouble(tpstring[1]), Double.parseDouble(tpstring[2]),
                        Float.parseFloat(tpstring[3]), Float.parseFloat(tpstring[4]));
            }

            fixdbFlags(fileDB, rname);
            newr = new Region(name, admins, members, leaders, new int[]{minX, minX, maxX, maxX}, new int[]{minZ, minZ, maxZ, maxZ}, minY, maxY, prior, world, date, RedProtect.get().getConfigManager().getDefFlagsValues(), welcome, value, tppoint, candel, canPurge);

            Set<String> defaultFlags = RedProtect.get().getConfigManager().getDefFlags();
            ConfigurationSection flagsSection = fileDB.getConfigurationSection(rname + ".flags");
            for (String flag : defaultFlags) {
                Object flagValue = flagsSection.get(flag);
                if (flagValue != null) {
                    newr.getFlags().put(flag, flagValue);
                } else {
                    newr.getFlags().put(flag, RedProtect.get().getConfigManager().getDefFlagsValues().get(flag));
                }
            }
            Set<String> regionFlags = flagsSection.getKeys(false);
            for (String flag : regionFlags) {
                if (!defaultFlags.contains(flag)) {
                    newr.getFlags().put(flag, flagsSection.get(flag));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            RedProtect.get().logger.severe("Error on load region " + rname);
            CoreUtil.printJarVersion();
        }
        return newr;
    }

    private static void fixdbFlags(YamlConfiguration db, String rname) {
        if (db.contains(rname + ".flags.mobs")) {
            db.set("spawn-monsters", db.get(rname + ".flags.mobs"));
            db.set(rname + ".flags.mobs", null);
        }
        if (db.contains(rname + ".flags.spawnpassives")) {
            db.set("spawn-animals", db.get(rname + ".flags.spawnpassives"));
            db.set(rname + ".flags.spawnpassives", null);
        }
    }

    @Override
    public void load() {
        try {
            RedProtect.get().logger.info("- Loading " + world + "'s regions...");

            if (!RedProtect.get().getConfigManager().configRoot().file_type.equals("mysql")) {
                if (RedProtect.get().getConfigManager().configRoot().flat_file.region_per_file) {
                    File f = new File(RedProtect.get().getDataFolder(), "data" + File.separator + world);
                    if (!f.exists()) {
                        f.mkdir();
                    }
                    File[] listOfFiles = f.listFiles();
                    for (File region : listOfFiles) {
                        if (region.getName().endsWith(".yml")) {
                            this.load(region.getPath());
                        }
                    }
                } else {
                    File oldf = new File(RedProtect.get().getDataFolder(), "data" + File.separator + world + ".yml");
                    File newf = new File(RedProtect.get().getDataFolder(), "data" + File.separator + "data_" + world + ".yml");
                    if (oldf.exists()) {
                        oldf.renameTo(newf);
                    }
                    this.load(RedProtect.get().getDataFolder() + File.separator + "data" + File.separator + "data_" + world + ".yml");
                }

            }
        } catch (Exception e) {
            CoreUtil.printJarVersion();
            e.printStackTrace();
        }
    }

    private void load(String path) {
        File f = new File(path);
        if (!f.exists()) {
            try {
                f.createNewFile();
            } catch (IOException e) {
                CoreUtil.printJarVersion();
                e.printStackTrace();
            }
        }

        if (!RedProtect.get().getConfigManager().configRoot().file_type.equals("mysql")) {
            YamlConfiguration fileDB = new YamlConfiguration();
            RedProtect.get().logger.debug(LogLevel.DEFAULT, "Load world " + this.world + ". File type: yml");
            try {
                fileDB.load(f);
            } catch (FileNotFoundException e) {
                RedProtect.get().logger.severe("DB file not found!");
                RedProtect.get().logger.severe("File:" + f.getName());
                CoreUtil.printJarVersion();
                e.printStackTrace();
            } catch (Exception e) {
                CoreUtil.printJarVersion();
                e.printStackTrace();
            }

            for (String rname : fileDB.getKeys(false)) {
                Region newr = loadRegion(fileDB, rname, this.world);
                if (newr == null) return;

                newr.setToSave(false);

                add(newr);
            }
        }
    }

    @Override
    public int save(boolean force) {
        int saved = 0;
        try {
            RedProtect.get().logger.debug(LogLevel.DEFAULT, "RegionManager.Save(): File type is " + RedProtect.get().getConfigManager().configRoot().file_type);
            String world = this.world;
            File datf;

            if (!RedProtect.get().getConfigManager().configRoot().file_type.equals("mysql")) {
                datf = new File(RedProtect.get().getDataFolder() + File.separator + "data", "data_" + world + ".yml");
                YamlConfiguration fileDB = new YamlConfiguration();
                Set<YamlConfiguration> yamls = new HashSet<>();
                for (Region r : regions.values()) {
                    if (r.getName() == null) {
                        continue;
                    }

                    if (RedProtect.get().getConfigManager().configRoot().flat_file.region_per_file) {
                        if (!r.toSave() && !force) {
                            continue;
                        }
                        fileDB = new YamlConfiguration();
                        datf = new File(RedProtect.get().getDataFolder() + File.separator + "data", world + File.separator + r.getName() + ".yml");
                    }

                    RedProtect.get().getUtil().addProps(fileDB, r);
                    saved++;

                    if (RedProtect.get().getConfigManager().configRoot().flat_file.region_per_file) {
                        yamls.add(fileDB);
                        saveYaml(fileDB, datf);
                        r.setToSave(false);
                    }
                }

                if (!RedProtect.get().getConfigManager().configRoot().flat_file.region_per_file) {
                    saveYaml(fileDB, datf);
                } else {
                    //remove deleted regions
                    File wfolder = new File(RedProtect.get().getDataFolder() + File.separator + "data", world);
                    if (wfolder.exists()) {
                        File[] listOfFiles = wfolder.listFiles();
                        if (listOfFiles != null) {
                            for (File region : listOfFiles) {
                                if (region.isFile() && !regions.containsKey(region.getName().replace(".yml", ""))) {
                                    region.delete();
                                }
                            }
                        }
                    }
                }

                if (force) RedProtect.get().logger.info("Saving " + this.world + "'s regions...");
            }
        } catch (Exception e4) {
            e4.printStackTrace();
        }
        return saved;
    }

    private void saveYaml(YamlConfiguration fileDB, File file) {
        try {
            fileDB.save(file);
        } catch (IOException e) {
            RedProtect.get().logger.severe("Error during save database file for world " + world + ": ");
            CoreUtil.printJarVersion();
            e.printStackTrace();
        }
    }

    @Override
    public void add(Region region) {
        Region previous = regions.put(region.getName(), region);
        // A same-named region being replaced (rename/redefine reuses the name) must leave
        // the index, otherwise the stale object keeps answering lookups.
        if (previous != null && previous != region) {
            indexRemove(previous);
        }
        indexAdd(region);
    }

    @Override
    public void remove(Region region) {
        if (regions.containsValue(region)) {
            regions.remove(region.getName());
        }
        indexRemove(region);
    }

    private void indexAdd(Region region) {
        if (chunkFootprint(region) > MAX_INDEXED_CHUNKS) {
            unindexedLargeRegions.add(region);
            return;
        }
        // Sized to 2: the overwhelming majority of chunks are covered by a single region,
        // and a default-capacity HashSet per chunk costs several times more memory.
        forEachChunk(region, (key) -> chunkIndex.computeIfAbsent(key, k -> new HashSet<>(2)).add(region));
    }

    private void indexRemove(Region region) {
        if (unindexedLargeRegions.remove(region)) {
            return;
        }
        forEachChunk(region, (key) -> {
            Set<Region> inChunk = chunkIndex.get(key);
            if (inChunk != null && inChunk.remove(region) && inChunk.isEmpty()) {
                chunkIndex.remove(key);
            }
        });
    }

    /** Number of chunks the region's horizontal MBR covers, as a long to avoid overflow. */
    private static long chunkFootprint(Region region) {
        long chunksX = (long) (region.getMaxMbrX() >> 4) - (region.getMinMbrX() >> 4) + 1L;
        long chunksZ = (long) (region.getMaxMbrZ() >> 4) - (region.getMinMbrZ() >> 4) + 1L;
        return chunksX * chunksZ;
    }

    private void forEachChunk(Region region, java.util.function.LongConsumer action) {
        // Arithmetic shift is intentional: block -1 belongs to chunk -1.
        int minChunkX = region.getMinMbrX() >> 4;
        int maxChunkX = region.getMaxMbrX() >> 4;
        int minChunkZ = region.getMinMbrZ() >> 4;
        int maxChunkZ = region.getMaxMbrZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                action.accept(chunkKey(chunkX, chunkZ));
            }
        }
    }

    /**
     * Regions that could contain the given horizontal position. Callers still have to test
     * the exact bounds (including Y); this only narrows the candidate set.
     */
    private Collection<Region> candidatesAt(int x, int z) {
        Set<Region> inChunk = chunkIndex.get(chunkKey(x >> 4, z >> 4));
        if (unindexedLargeRegions.isEmpty()) {
            return inChunk == null ? Collections.emptySet() : inChunk;
        }
        if (inChunk == null) {
            return unindexedLargeRegions;
        }
        // A region is in exactly one of the two collections, so this cannot duplicate.
        List<Region> candidates = new ArrayList<>(inChunk.size() + unindexedLargeRegions.size());
        candidates.addAll(inChunk);
        candidates.addAll(unindexedLargeRegions);
        return candidates;
    }

    @Override
    public Set<Region> getLeaderRegions(String pname) {
        SortedSet<Region> regionsp = new TreeSet<>(Comparator.comparing(Region::getName));
        for (Region r : regions.values()) {
            if (r.isLeader(pname)) {
                regionsp.add(r);
            }
        }
        return regionsp;
    }

    @Override
    public Set<Region> getAdminRegions(String uuid) {
        SortedSet<Region> regionsp = new TreeSet<>(Comparator.comparing(Region::getName));
        for (Region r : regions.values()) {
            if (r.isLeader(uuid) || r.isAdmin(uuid)) {
                regionsp.add(r);
            }
        }
        return regionsp;
    }

    @Override
    public Set<Region> getMemberRegions(String uuid) {
        SortedSet<Region> regionsp = new TreeSet<>(Comparator.comparing(Region::getName));
        for (Region r : regions.values()) {
            if (r.isLeader(uuid) || r.isAdmin(uuid) || r.isMember(uuid)) {
                regionsp.add(r);
            }
        }
        return regionsp;
    }

    @Override
    public Region getRegion(String rname) {
        Optional<Map.Entry<String, Region>> optional = regions.entrySet().stream().filter(r -> r.getKey().equalsIgnoreCase(rname)).findFirst();
        return optional.map(Map.Entry::getValue).orElse(null);
    }

    @Override
    public int getTotalRegionSize(String uuid) {
        Set<Region> regionslist = new HashSet<>();
        for (Region r : regions.values()) {
            if (r.isLeader(uuid)) {
                regionslist.add(r);
            }
        }
        int total = 0;
        for (Region r2 : regionslist) {
            total += RedProtect.get().getUtil().simuleTotalRegionSize(uuid, r2);
        }
        return total;
    }

    @Override
    public Set<Region> getRegionsNear(int px, int pz, int radius) {
        SortedSet<Region> ret = new TreeSet<>(Comparator.comparing(Region::getName));

        for (Region r : regions.values()) {
            RedProtect.get().logger.debug(LogLevel.DEFAULT, "Radius: " + radius);
            RedProtect.get().logger.debug(LogLevel.DEFAULT, "X radius: " + Math.abs(r.getCenterX() - px) + " - Z radius: " + Math.abs(r.getCenterZ() - pz));
            if (Math.abs(r.getCenterX() - px) <= radius && Math.abs(r.getCenterZ() - pz) <= radius) {
                ret.add(r);
            }
        }
        return ret;
    }

    public String getWorld() {
        return this.world;
    }

    @Override
    public Set<Region> getInnerRegions(Region region) {
        Set<Region> regionl = new HashSet<>();
        regions.values().forEach(r -> {
            if (r.getMaxMbrX() <= region.getMaxMbrX() &&
                    r.getMaxY() <= region.getMaxY() &&
                    r.getMaxMbrZ() <= region.getMaxMbrZ() &&
                    r.getMinMbrX() >= region.getMinMbrX() &&
                    r.getMinY() >= region.getMinY() &&
                    r.getMinMbrZ() >= region.getMinMbrZ()) {
                regionl.add(r);
            }
        });
        return regionl;
    }

    @Override
    public Set<Region> getRegions(int x, int y, int z) {
        Set<Region> regionl = new HashSet<>();
        candidatesAt(x, z).forEach(r -> {
            if (x <= r.getMaxMbrX() &&
                    x >= r.getMinMbrX() &&
                    y <= r.getMaxY() &&
                    y >= r.getMinY() &&
                    z <= r.getMaxMbrZ() &&
                    z >= r.getMinMbrZ()) {
                regionl.add(r);
            }
        });
        return regionl;
    }

    @Override
    public Region getTopRegion(int x, int y, int z) {
        Map<Integer, Region> regionlist = new HashMap<>();
        int max = 0;
        for (Region r : candidatesAt(x, z)) {
            if (x <= r.getMaxMbrX() && x >= r.getMinMbrX() && y <= r.getMaxY() && y >= r.getMinY() && z <= r.getMaxMbrZ() && z >= r.getMinMbrZ()) {
                if (regionlist.containsKey(r.getPrior())) {
                    Region reg1 = regionlist.get(r.getPrior());
                    int Prior = r.getPrior();
                    if (reg1.getArea() >= r.getArea()) {
                        r.setPrior(Prior + 1);
                    } else {
                        reg1.setPrior(Prior + 1);
                    }
                }
                regionlist.put(r.getPrior(), r);
            }
        }
        if (!regionlist.isEmpty()) {
            max = Collections.max(regionlist.keySet());
        }
        return regionlist.get(max);
    }

    @Override
    public Region getLowRegion(int x, int y, int z) {
        Map<Integer, Region> regionlist = new HashMap<>();
        int min = 0;
        for (Region r : candidatesAt(x, z)) {
            if (x <= r.getMaxMbrX() && x >= r.getMinMbrX() && y <= r.getMaxY() && y >= r.getMinY() && z <= r.getMaxMbrZ() && z >= r.getMinMbrZ()) {
                if (regionlist.containsKey(r.getPrior())) {
                    Region reg1 = regionlist.get(r.getPrior());
                    int Prior = r.getPrior();
                    if (reg1.getArea() >= r.getArea()) {
                        r.setPrior(Prior + 1);
                    } else {
                        reg1.setPrior(Prior + 1);
                    }
                }
                regionlist.put(r.getPrior(), r);
            }
        }
        if (!regionlist.isEmpty()) {
            min = Collections.min(regionlist.keySet());
        }
        return regionlist.get(min);
    }

    @Override
    public Map<Integer, Region> getGroupRegion(int x, int y, int z) {
        Map<Integer, Region> regionlist = new HashMap<>();
        for (Region r : candidatesAt(x, z)) {
            if (x <= r.getMaxMbrX() && x >= r.getMinMbrX() && y <= r.getMaxY() && y >= r.getMinY() && z <= r.getMaxMbrZ() && z >= r.getMinMbrZ()) {
                if (regionlist.containsKey(r.getPrior())) {
                    Region reg1 = regionlist.get(r.getPrior());
                    int Prior = r.getPrior();
                    if (reg1.getArea() >= r.getArea()) {
                        r.setPrior(Prior + 1);
                    } else {
                        reg1.setPrior(Prior + 1);
                    }
                }
                regionlist.put(r.getPrior(), r);
            }
        }
        return regionlist;
    }

    @Override
    public Set<Region> getAllRegions() {
        SortedSet<Region> allregions = new TreeSet<>(Comparator.comparing(Region::getName));
        allregions.addAll(regions.values());
        return allregions;
    }

    @Override
    public void clearRegions() {
        regions.clear();
        chunkIndex.clear();
        unindexedLargeRegions.clear();
    }

    @Override
    public void updateLiveRegion(String rname, String column, Object value) {
    }

    @Override
    public void closeConn() {
    }

    @Override
    public int getTotalRegionNum() {
        return regions.size();
    }

    @Override
    public void updateLiveFlags(String rname, String flag, String value) {
    }

    @Override
    public void removeLiveFlags(String rname, String flag) {
    }

    @Override
    public long getCanPurgeCount(String uuid, boolean canpurge) {
        return regions.values().stream().filter(r -> r.canPurge() == canpurge && r.isLeader(uuid)).count();
    }

    @Override
    public void updateLiveMembers(String rname, String leaders, String admins, String members) {
    }
}
