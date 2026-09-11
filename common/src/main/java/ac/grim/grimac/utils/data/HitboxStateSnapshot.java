package ac.grim.grimac.utils.data;

/**
 * Immutable feet-position envelope and fixed dimensions for one interpolation
 * state. Zero-width position ranges represent a known coordinate. Consumers
 * must validate the scalars and exclude uncertain poses before using them.
 */
public record HitboxStateSnapshot(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ,
                                  double width, double height, double depth) {
}
