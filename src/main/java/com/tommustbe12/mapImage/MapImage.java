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

        // make sure section exists
        if (!config.isConfigurationSection("maps")) {
            config.createSection("maps");
            saveConfig();
        }

        ConfigurationSection section = config.getConfigurationSection("maps");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int mapId = Integer.parseInt(key);
                String url = section.getString(key);
                reRenderMap(mapId, url);
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
            player.sendMessage(ChatColor.RED + "Usage: /mapimage <image-url>");
            return true;
        }

        String imageUrl = args[0];

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (InputStream in = new URL(imageUrl).openStream()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    player.sendMessage(ChatColor.RED + "Failed to load image.");
                    return;
                }

                BufferedImage scaled = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.drawImage(image, 0, 0, 128, 128, null);
                g.dispose();

                byte[] pixels = new byte[128 * 128];
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        Color color = new Color(scaled.getRGB(x, y));
                        byte mcColor = MapPalette.matchColor(color);
                        pixels[y * 128 + x] = mcColor;
                    }
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    MapView mapView = Bukkit.createMap(player.getWorld());
                    int mapId = mapView.getId();

                    // clear renderers
                    mapView.getRenderers().forEach(mapView::removeRenderer);

                    mapView.addRenderer(new PersistentRenderer(pixels));

                    // save config
                    config.set("maps." + mapId, imageUrl);
                    saveConfig();

                    // give map
                    ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                    MapMeta meta = (MapMeta) mapItem.getItemMeta();
                    meta.setMapView(mapView);
                    mapItem.setItemMeta(meta);

                    player.getInventory().addItem(mapItem);
                    player.sendMessage(ChatColor.GREEN + "Map created and saved!");
                });

            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "Error loading image: " + e.getMessage());
                e.printStackTrace();
            }
        });

        return true;
    }

    private void reRenderMap(int mapId, String url) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (InputStream in = new URL(url).openStream()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) return;

                BufferedImage scaled = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.drawImage(image, 0, 0, 128, 128, null);
                g.dispose();

                byte[] pixels = new byte[128 * 128];
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        Color color = new Color(scaled.getRGB(x, y));
                        byte mcColor = MapPalette.matchColor(color);
                        pixels[y * 128 + x] = mcColor;
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
