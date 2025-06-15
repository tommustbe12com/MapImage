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
import org.bukkit.map.*;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;

public class MapImage extends JavaPlugin implements CommandExecutor {

    private FileConfiguration config;

    @Override
    public void onEnable() {
        this.getCommand("mapimage").setExecutor(this);
        saveDefaultConfig();
        config = getConfig();

        // Ensure maps section exists
        if (!config.isConfigurationSection("maps")) {
            config.createSection("maps");
            saveConfig();
        }

        ConfigurationSection section = config.getConfigurationSection("maps");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int mapId = Integer.parseInt(key);
                    ConfigurationSection entry = section.getConfigurationSection(key);
                    if (entry == null) continue;

                    String url = entry.getString("url");
                    int row = entry.getInt("row", 0);
                    int col = entry.getInt("col", 0);
                    int totalRows = entry.getInt("totalRows", 1);
                    int totalCols = entry.getInt("totalCols", 1);

                    reRenderMap(mapId, url, row, col, totalRows, totalCols);
                } catch (NumberFormatException ignored) {}
            }
        }
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
            player.sendMessage(ChatColor.RED + "Usage: /mapimage <image-url> [rowsxcols]");
            return true;
        }

        String imageUrl = args[0];
        int rows = 1;
        int cols = 1;

        if (args.length >= 2 && args[1].matches("\\d+x\\d+")) {
            String[] parts = args[1].split("x");
            rows = Integer.parseInt(parts[0]);
            cols = Integer.parseInt(parts[1]);
        }

        final int finalRows = rows;
        final int finalCols = cols;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (InputStream in = new URL(imageUrl).openStream()) {
                BufferedImage original = ImageIO.read(in);
                if (original == null) {
                    player.sendMessage(ChatColor.RED + "Failed to load image.");
                    return;
                }

                BufferedImage scaled = new BufferedImage(finalCols * 128, finalRows * 128, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.drawImage(original, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
                g.dispose();

                byte[][] tilePixels = new byte[finalRows * finalCols][128 * 128];

                for (int tileY = 0; tileY < finalRows; tileY++) {
                    for (int tileX = 0; tileX < finalCols; tileX++) {
                        BufferedImage tile = scaled.getSubimage(tileX * 128, tileY * 128, 128, 128);
                        byte[] pixels = new byte[128 * 128];
                        for (int y = 0; y < 128; y++) {
                            for (int x = 0; x < 128; x++) {
                                Color color = new Color(tile.getRGB(x, y));
                                pixels[y * 128 + x] = MapPalette.matchColor(color);
                            }
                        }
                        tilePixels[tileY * finalCols + tileX] = pixels;
                    }
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    ConfigurationSection mapSection = config.getConfigurationSection("maps");
                    if (mapSection == null) {
                        mapSection = config.createSection("maps");
                    }

                    for (int i = 0; i < tilePixels.length; i++) {
                        int row = i / finalCols;
                        int col = i % finalCols;

                        MapView mapView = Bukkit.createMap(player.getWorld());
                        int mapId = mapView.getId();

                        mapView.getRenderers().forEach(mapView::removeRenderer);
                        mapView.addRenderer(new PersistentRenderer(tilePixels[i]));

                        ConfigurationSection entry = config.createSection("maps." + mapId);
                        entry.set("url", imageUrl);
                        entry.set("row", row);
                        entry.set("col", col);
                        entry.set("totalRows", finalRows);
                        entry.set("totalCols", finalCols);

                        player.getInventory().addItem(getMapItem(mapView));
                    }

                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Created map image grid: " + finalRows + "x" + finalCols);
                });

            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "Error loading image: " + e.getMessage());
                e.printStackTrace();
            }
        });

        return true;
    }

    private ItemStack getMapItem(MapView mapView) {
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(mapView);
        mapItem.setItemMeta(meta);
        return mapItem;
    }

    private void reRenderMap(int mapId, String url, int row, int col, int totalRows, int totalCols) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (InputStream in = new URL(url).openStream()) {
                BufferedImage original = ImageIO.read(in);
                if (original == null) return;

                BufferedImage scaled = new BufferedImage(totalCols * 128, totalRows * 128, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.drawImage(original, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
                g.dispose();

                BufferedImage tile = scaled.getSubimage(col * 128, row * 128, 128, 128);

                byte[] pixels = new byte[128 * 128];
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        Color color = new Color(tile.getRGB(x, y));
                        pixels[y * 128 + x] = MapPalette.matchColor(color);
                    }
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    MapView mapView = Bukkit.getMap((short) mapId);
                    if (mapView == null) return;

                    mapView.getRenderers().forEach(mapView::removeRenderer);
                    mapView.addRenderer(new PersistentRenderer(pixels));
                });

            } catch (Exception ignored) {}
        });
    }

    private static class PersistentRenderer extends MapRenderer {
        private final byte[] pixels;
        private boolean done = false;

        public PersistentRenderer(byte[] pixels) {
            this.pixels = pixels;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (done) return;
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    canvas.setPixel(x, y, pixels[y * 128 + x]);
                }
            }
            done = true;
        }
    }
}
