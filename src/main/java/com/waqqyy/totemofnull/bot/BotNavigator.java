package com.waqqyy.totemofnull.bot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Small block-grid A* for the bots: walk, jump up one block, drop down up to three.
 *
 * <p>When the goal cannot be reached within the node budget the result is the path to the
 * explored node closest to the goal, flagged as incomplete.</p>
 */
public final class BotNavigator {

	/** A block position a player can stand in (feet block). */
	public record Node(int x, int y, int z) {
	}

	/** A path, start excluded. {@code complete} is false when the goal was not reached. */
	public record Result(List<Node> nodes, boolean complete) {
	}

	private static final int MAX_DROP = 3;
	private static final int MAX_RANGE = 48;

	private static final int[][] CARDINAL = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
	private static final int[][] DIAGONAL = { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };

	private final World world;
	private final Map<Node, Boolean> passableCache = new HashMap<>();

	private BotNavigator(World world) {
		this.world = world;
	}

	public static Result findPath(Location from, Location to, int nodeBudget) {
		return new BotNavigator(from.getWorld()).search(from, to, nodeBudget);
	}

	private record Entry(Node node, double g, double f) {
	}

	private Result search(Location from, Location to, int nodeBudget) {
		Node start = settle(from);
		Node goal = settle(to);

		Map<Node, Double> best = new HashMap<>();
		Map<Node, Node> parent = new HashMap<>();
		PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
		best.put(start, 0.0);
		open.add(new Entry(start, 0.0, distance(start, goal)));

		Node closest = start;
		double closestDistance = distance(start, goal);
		int expanded = 0;

		while (!open.isEmpty() && expanded < nodeBudget) {
			Entry entry = open.poll();
			Node node = entry.node();
			if (entry.g() > best.get(node)) {
				continue; // stale queue entry
			}
			expanded++;

			double h = distance(node, goal);
			if (h < closestDistance) {
				closestDistance = h;
				closest = node;
			}
			if (Math.abs(node.x() - goal.x()) <= 1 && Math.abs(node.z() - goal.z()) <= 1
					&& Math.abs(node.y() - goal.y()) <= 1) {
				return new Result(trace(parent, node), true);
			}

			for (Entry next : neighbours(node)) {
				Node n = next.node();
				if (Math.abs(n.x() - start.x()) > MAX_RANGE || Math.abs(n.z() - start.z()) > MAX_RANGE) {
					continue;
				}
				double g = entry.g() + next.g();
				Double known = best.get(n);
				if (known != null && known <= g) {
					continue;
				}
				best.put(n, g);
				parent.put(n, node);
				open.add(new Entry(n, g, g + distance(n, goal)));
			}
		}
		return new Result(trace(parent, closest), false);
	}

	private static List<Node> trace(Map<Node, Node> parent, Node end) {
		List<Node> path = new ArrayList<>();
		Node node = end;
		while (parent.containsKey(node)) {
			path.add(node);
			node = parent.get(node);
		}
		Collections.reverse(path);
		return path;
	}

	/** Moves reachable from {@code node}; the entry's g is the step cost. */
	private List<Entry> neighbours(Node node) {
		List<Entry> out = new ArrayList<>(8);
		int x = node.x();
		int y = node.y();
		int z = node.z();

		for (int[] d : CARDINAL) {
			int nx = x + d[0];
			int nz = z + d[1];
			if (standable(nx, y, nz)) {
				out.add(new Entry(new Node(nx, y, nz), 1.0, 0));
			} else if (passable(x, y + 2, z) && standable(nx, y + 1, nz)) {
				out.add(new Entry(new Node(nx, y + 1, nz), 1.5, 0)); // jump up
			} else if (passable(nx, y, nz) && passable(nx, y + 1, nz)) {
				for (int drop = 1; drop <= MAX_DROP; drop++) {
					if (!passable(nx, y - drop, nz)) {
						break;
					}
					if (standable(nx, y - drop, nz)) {
						out.add(new Entry(new Node(nx, y - drop, nz), 1.0 + drop * 0.5, 0));
						break;
					}
				}
			}
		}
		for (int[] d : DIAGONAL) {
			int nx = x + d[0];
			int nz = z + d[1];
			// No corner cutting: both adjacent cells must be walkable too.
			if (standable(nx, y, nz) && standable(nx, y, z) && standable(x, y, nz)) {
				out.add(new Entry(new Node(nx, y, nz), 1.414, 0));
			}
		}
		return out;
	}

	/** Snaps a location to the feet block of whatever is standing there (they may be mid-jump). */
	private Node settle(Location location) {
		int x = location.getBlockX();
		int y = location.getBlockY();
		int z = location.getBlockZ();
		for (int drop = 0; drop <= MAX_DROP; drop++) {
			if (standable(x, y - drop, z)) {
				return new Node(x, y - drop, z);
			}
		}
		return new Node(x, y, z);
	}

	private boolean standable(int x, int y, int z) {
		if (!passable(x, y, z) || !passable(x, y + 1, z)) {
			return false;
		}
		// Solid floor, or water to swim in.
		return !passable(x, y - 1, z) || blockType(x, y, z) == Material.WATER;
	}

	private boolean passable(int x, int y, int z) {
		Node key = new Node(x, y, z);
		Boolean cached = passableCache.get(key);
		if (cached != null) {
			return cached;
		}
		boolean result = computePassable(x, y, z);
		passableCache.put(key, result);
		return result;
	}

	private boolean computePassable(int x, int y, int z) {
		if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
			return true;
		}
		// Never load chunks from here: treat unloaded terrain as a wall.
		if (!world.isChunkLoaded(x >> 4, z >> 4)) {
			return false;
		}
		Block block = world.getBlockAt(x, y, z);
		Material type = block.getType();
		if (type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE) {
			return false;
		}
		return block.isPassable();
	}

	private Material blockType(int x, int y, int z) {
		if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
			return Material.AIR;
		}
		return world.getBlockAt(x, y, z).getType();
	}

	private static double distance(Node a, Node b) {
		double dx = a.x() - b.x();
		double dy = a.y() - b.y();
		double dz = a.z() - b.z();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
