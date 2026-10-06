package ch.ge.ael.geteleverse.service;

/**
 * Coin de document détecté, en pixels de l'image source.
 *
 * <p>Record Java volontairement, et non {@code org.bytedeco.opencv.opencv_core.Point2f} :
 * un {@code Point2f} est un {@code Pointer} JavaCPP, donc une allocation native
 * assortie d'un deallocator et d'une phantom reference. La cascade de détection en
 * produit quatre par candidat évalué, et elle évalue un candidat par contour, par
 * enveloppe convexe et par taille de noyau — soit couramment plusieurs milliers
 * d'allocations natives pour une seule photo chargée. Ces octets n'étant rendus
 * qu'au passage du GC, ils pesaient sur le budget natif de JavaCPP (cf. le
 * commentaire de {@code PerspectiveCorrectionService} sur les {@code BytePointer}).
 * Un record ne coûte que du tas Java, que le GC jeune ramasse sans effort.</p>
 *
 * @param x abscisse en pixels
 * @param y ordonnée en pixels
 */
public record Corner(float x, float y) {}
