package com.tommustbe12.mapImage;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapView;
import org.bukkit.map.MapRenderer;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class MapImage extends JavaPlugin implements CommandExecutor {

    private FileConfiguration config;
    private final Queue<Runnable> mapQueue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, MapData> pendingMaps = new ConcurrentHashMap<>();

    private static final int TILE_SIZE = 128;
    private static final int MAX_DIMENSION = 512;
    private static final int MAPS_PER_TICK = 2;

    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"
    );

    @Override
    public void onEnable() {
        this.getCommand("mapimage").setExecutor(this);
        saveDefaultConfig();
        config = getConfig();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                for (int i = 0; i < MAPS_PER_TICK; i++) {
                    Runnable job = mapQueue.poll();
                    if (job == null) break;
                    try {
                        job.run();
                    } catch (Throwable t) {
                        getLogger().log(Level.SEVERE, "Exception while running queued map job", t);
                    }
                }
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Map queue processor failed", t);
            }
        }, 1L, 1L);

        if (config.isConfigurationSection("maps")) {
            ConfigurationSection section = config.getConfigurationSection("maps");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        int mapId = Integer.parseInt(key);
                        ConfigurationSection entry = section.getConfigurationSection(key);
                        if (entry == null) continue;

                        String source = entry.getString("source", "url");
                        int row = entry.getInt("row");
                        int col = entry.getInt("col");
                        int totalRows = entry.getInt("totalRows", 1);
                        int totalCols = entry.getInt("totalCols", 1);

                        if ("local".equals(source)) {
                            String fileName = entry.getString("file");
                            if (fileName != null) {
                                int finalMapId = mapId;
                                mapQueue.add(() -> reRenderMapFromFile(finalMapId, fileName, row, col, totalRows, totalCols));
                            }
                        } else {
                            String url = entry.getString("url");
                            if (url != null) {
                                int finalMapId = mapId;
                                mapQueue.add(() -> reRenderMapAsync(finalMapId, url, row, col, totalRows, totalCols));
                            }
                        }

                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }

    @Override
    public void onDisable() {
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!player.isOp() && !player.hasPermission("mapimage.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /mapimage <image-url-or-filename> [rowsxcols]");
            return true;
        }

        String input = args[0];
        int rows = 1, cols = 1;
        if (args.length >= 2 && args[1].matches("\\d+x\\d+")) {
            String[] parts = args[1].split("x");
            rows = Integer.parseInt(parts[0]);
            cols = Integer.parseInt(parts[1]);
        }

        final int finalRows = rows;
        final int finalCols = cols;

        boolean isUrl = input.startsWith("http://") || input.startsWith("https://");

        if (isUrl) {
            final String imageUrl = input;
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    BufferedImage original = ImageIO.read(in);
                    if (original == null) {
                        player.sendMessage(ChatColor.RED + "Failed to load image from URL.");
                        return;
                    }
                    processAndQueueTiles(original, imageUrl, "url", null, finalRows, finalCols, player);
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Error loading image: " + e.getMessage());
                    getLogger().log(Level.WARNING, "Error processing image URL: " + imageUrl, e);
                }
            });

        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                File imageFile = resolveLocalFile(input);
                if (imageFile == null) {
                    player.sendMessage(ChatColor.RED + "Could not find image file '" + input + "' in the plugin data folder.");
                    player.sendMessage(ChatColor.YELLOW + "Supported formats: .png .jpg .jpeg .gif .bmp .webp");
                    return;
                }
                try {
                    BufferedImage original = ImageIO.read(imageFile);
                    if (original == null) {
                        player.sendMessage(ChatColor.RED + "Failed to read image file: " + imageFile.getName());
                        return;
                    }
                    player.sendMessage(ChatColor.GREEN + "Loading local image: " + imageFile.getName());
                    processAndQueueTiles(original, null, "local", imageFile.getName(), finalRows, finalCols, player);
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Error reading local image: " + e.getMessage());
                    getLogger().log(Level.WARNING, "Error processing local image: " + imageFile.getAbsolutePath(), e);
                }
            });
        }

        return true;
    }

    private void processAndQueueTiles(BufferedImage original, String url, String source, String fileName,
                                      int finalRows, int finalCols, Player player) {
        int fullW = finalCols * TILE_SIZE;
        int fullH = finalRows * TILE_SIZE;

        BufferedImage scaled = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        Image tmp = original.getScaledInstance(fullW, fullH, Image.SCALE_SMOOTH);
        g.drawImage(tmp, 0, 0, null);
        g.dispose();
        original.flush();

        for (int tileY = 0; tileY < finalRows; tileY++) {
            for (int tileX = 0; tileX < finalCols; tileX++) {
                BufferedImage tile = scaled.getSubimage(tileX * TILE_SIZE, tileY * TILE_SIZE, TILE_SIZE, TILE_SIZE);

                byte[] pixels = new byte[TILE_SIZE * TILE_SIZE];
                for (int y = 0; y < TILE_SIZE; y++) {
                    for (int x = 0; x < TILE_SIZE; x++) {
                        java.awt.Color c = new java.awt.Color(tile.getRGB(x, y));
                        pixels[y * TILE_SIZE + x] = org.bukkit.map.MapPalette.matchColor(c);
                    }
                }
                tile.flush();

                final int row = tileY;
                final int col = tileX;
                final byte[] tilePixels = pixels;

                mapQueue.add(() -> createMapOnMainThread(
                        player.getWorld().getName(), url, source, fileName,
                        row, col, finalRows, finalCols, tilePixels, player));
            }
        }

        scaled.flush();
        player.sendMessage(ChatColor.GREEN + "Queued map image creation: " + finalRows + "x" + finalCols);
    }

    private void createMapOnMainThread(String worldName, String url, String source, String fileName,
                                       int row, int col, int totalRows, int totalCols,
                                       byte[] pixels, Player giver) {
        try {
            MapView mapView = Bukkit.createMap(Bukkit.getWorld(worldName));
            int mapId = mapView.getId();

            mapView.getRenderers().forEach(mapView::removeRenderer);
            mapView.addRenderer(new PersistentRenderer(pixels));

            try {
                mapView.setUnlimitedTracking(true);
                mapView.setTrackingPosition(false);
            } catch (Throwable ignored) {}

            // persist
            ConfigurationSection entry = config.createSection("maps." + mapId);
            entry.set("source", source);
            if ("local".equals(source)) {
                entry.set("file", fileName);
            } else {
                entry.set("url", url);
            }
            entry.set("row", row);
            entry.set("col", col);
            entry.set("totalRows", totalRows);
            entry.set("totalCols", totalCols);
            saveConfig();

            giver.getInventory().addItem(createMapItem(mapView));

        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Failed to create map on main thread", t);
        }
    }

    private void reRenderMapAsync(int mapId, String url, int row, int col, int totalRows, int totalCols) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (InputStream in = new URL(url).openStream()) {
                BufferedImage original = ImageIO.read(in);
                if (original == null) return;

                byte[] pixels = extractTilePixels(original, row, col, totalRows, totalCols);

                mapQueue.add(() -> applyRendererToExistingMap(mapId, pixels));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to re-download map image: " + url, e);
            }
        });
    }

    private void reRenderMapFromFile(int mapId, String fileName, int row, int col, int totalRows, int totalCols) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            File imageFile = resolveLocalFile(fileName);
            if (imageFile == null) {
                getLogger().warning("Cannot re-render map " + mapId + ": local file not found: " + fileName);
                return;
            }
            try {
                BufferedImage original = ImageIO.read(imageFile);
                if (original == null) return;

                byte[] pixels = extractTilePixels(original, row, col, totalRows, totalCols);

                mapQueue.add(() -> applyRendererToExistingMap(mapId, pixels));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to re-render map " + mapId + " from file: " + fileName, e);
            }
        });
    }

    private byte[] extractTilePixels(BufferedImage original, int row, int col, int totalRows, int totalCols) {
        int fullW = totalCols * TILE_SIZE;
        int fullH = totalRows * TILE_SIZE;
        BufferedImage scaled = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        Image tmp = original.getScaledInstance(fullW, fullH, Image.SCALE_SMOOTH);
        g.drawImage(tmp, 0, 0, null);
        g.dispose();
        original.flush();

        BufferedImage tile = scaled.getSubimage(col * TILE_SIZE, row * TILE_SIZE, TILE_SIZE, TILE_SIZE);
        byte[] pixels = new byte[TILE_SIZE * TILE_SIZE];
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                java.awt.Color c = new java.awt.Color(tile.getRGB(x, y));
                pixels[y * TILE_SIZE + x] = org.bukkit.map.MapPalette.matchColor(c);
            }
        }
        tile.flush();
        scaled.flush();
        return pixels;
    }

    private void applyRendererToExistingMap(int mapId, byte[] pixels) {
        try {
            MapView mapView = Bukkit.getMap((short) mapId);
            if (mapView == null) return;
            mapView.getRenderers().forEach(mapView::removeRenderer);
            mapView.addRenderer(new PersistentRenderer(pixels));
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to re-render map " + mapId, t);
        }
    }

    private File resolveLocalFile(String name) {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        File direct = new File(dataFolder, name);
        if (direct.exists() && direct.isFile()) return direct;

        String baseName = name;
        for (String ext : IMAGE_EXTENSIONS) {
            if (name.toLowerCase().endsWith(ext)) {
                baseName = name.substring(0, name.length() - ext.length());
                break;
            }
        }

        for (String ext : IMAGE_EXTENSIONS) {
            File candidate = new File(dataFolder, baseName + ext);
            if (candidate.exists() && candidate.isFile()) return candidate;
        }

        return null;
    }

    private ItemStack createMapItem(MapView view) {
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        mapItem.setItemMeta(meta);
        return mapItem;
    }

    private static class PersistentRenderer extends MapRenderer {
        private final byte[] pixels;

        public PersistentRenderer(byte[] pixels) {
            super(true);
            this.pixels = pixels;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, org.bukkit.entity.Player player) {
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    canvas.setPixel(x, y, pixels[y * TILE_SIZE + x]);
                }
            }
        }
    }

    private static class MapData {
        final String url;
        final int row;
        final int col;
        final int totalRows;
        final int totalCols;

        MapData(String url, int row, int col, int totalRows, int totalCols) {
            this.url = url;
            this.row = row;
            this.col = col;
            this.totalRows = totalRows;
            this.totalCols = totalCols;
        }
    }
}