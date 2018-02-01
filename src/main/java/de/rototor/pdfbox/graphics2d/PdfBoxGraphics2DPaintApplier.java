package de.rototor.pdfbox.graphics2d;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType3;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;

import java.awt.*;
import java.util.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Default paint mapper.
 * 
 * NOTE: Objects of this class are stateful and *not* thread safe!
 */
public class PdfBoxGraphics2DPaintApplier implements IPdfBoxGraphics2DPaintApplier {
	@SuppressWarnings("WeakerAccess")
	protected PDDocument document;
	@SuppressWarnings("WeakerAccess")
	protected PDPageContentStream contentStream;
	@SuppressWarnings("WeakerAccess")
	protected IPdfBoxGraphics2DColorMapper colorMapper;
	@SuppressWarnings("WeakerAccess")
	protected IPdfBoxGraphics2DImageEncoder imageEncoder;
	@SuppressWarnings("WeakerAccess")
	protected PDResources resources;
	@SuppressWarnings("WeakerAccess")
	protected PDExtendedGraphicsState pdExtendedGraphicsState;
	@SuppressWarnings("WeakerAccess")
	protected Composite composite;
	private COSDictionary dictExtendedState;
	private ExtGStateCache extGStateCache = new ExtGStateCache();
	private PDShadingCache shadingCache = new PDShadingCache();

	@Override
	public PDShading applyPaint(Paint paint, PDPageContentStream contentStream, AffineTransform tf, IPaintEnv env)
			throws IOException {
		this.document = env.getDocument();
		this.resources = env.getResources();
		this.contentStream = contentStream;
		this.colorMapper = env.getColorMapper();
		this.imageEncoder = env.getImageEncoder();
		this.composite = env.getComposite();
		this.pdExtendedGraphicsState = null;
		PDShading shading = applyPaint(paint, tf);
		if (pdExtendedGraphicsState != null)
			contentStream.setGraphicsStateParameters(extGStateCache.makeUnqiue(pdExtendedGraphicsState));
		return shading;
	}

	@SuppressWarnings("WeakerAccess")
	protected void applyAsStrokingColor(Color color) throws IOException {

		contentStream.setStrokingColor(colorMapper.mapColor(contentStream, color));
		contentStream.setNonStrokingColor(colorMapper.mapColor(contentStream, color));

		int alpha = color.getAlpha();
		if (alpha < 255) {
			/*
			 * This is semitransparent
			 */
			ensureExtendedState();
			Float strokingAlphaConstant = pdExtendedGraphicsState.getStrokingAlphaConstant();
			if (strokingAlphaConstant == null)
				strokingAlphaConstant = 1f;
			pdExtendedGraphicsState.setStrokingAlphaConstant(strokingAlphaConstant * (alpha / 255f));
			Float nonStrokingAlphaConstant = pdExtendedGraphicsState.getNonStrokingAlphaConstant();
			if (nonStrokingAlphaConstant == null)
				nonStrokingAlphaConstant = 1f;
			pdExtendedGraphicsState.setNonStrokingAlphaConstant(nonStrokingAlphaConstant * (alpha / 255f));
		}
	}

	private void ensureExtendedState() {
		if (pdExtendedGraphicsState == null) {
			this.dictExtendedState = new COSDictionary();
			this.dictExtendedState.setItem(COSName.TYPE, COSName.EXT_G_STATE);
			pdExtendedGraphicsState = new PDExtendedGraphicsState(this.dictExtendedState);
		}
	}

	private PDShading applyPaint(Paint paint, AffineTransform tf) throws IOException {
		applyComposite();
		if (paint instanceof Color) {
			applyAsStrokingColor((Color) paint);
		} else if (paint.getClass().getSimpleName().equals("LinearGradientPaint")) {
			return shadingCache.makeUnqiue(buildLinearGradientShading(paint, tf));
		} else if (paint.getClass().getSimpleName().equals("RadialGradientPaint")) {
			return shadingCache.makeUnqiue(buildRadialGradientShading(paint, tf));
		} else if (paint instanceof GradientPaint) {
			return shadingCache.makeUnqiue(buildGradientShading(tf, (GradientPaint) paint));
		} else if (paint instanceof TexturePaint) {
			applyTexturePaint((TexturePaint) paint);
		} else {
			System.err.println("Don't know paint " + paint.getClass().getName());
		}
		return null;
	}

	private void applyComposite() {
		/*
		 * If we don't have a composite we don't need to do any mapping
		 */
		if (this.composite == null)
			return;

		// Possibly set the alpha constant
		float alpha = 1;
		COSName blendMode = COSName.COMPATIBLE;
		int rule = AlphaComposite.SRC;

		if (this.composite instanceof AlphaComposite) {
			AlphaComposite composite = (AlphaComposite) this.composite;
			alpha = composite.getAlpha();
			rule = composite.getRule();
		} else if (this.composite.getClass().getSimpleName().equals("SVGComposite")) {
			/*
			 * Batik Composite
			 */
			alpha = getPropertyValue(this.composite, "alpha");
			rule = getPropertyValue(this.composite, "rule");
		} else {
			System.err.println("Unknown composite " + this.composite.getClass().getSimpleName());
		}

		ensureExtendedState();
		if (alpha < 1) {
			pdExtendedGraphicsState.setStrokingAlphaConstant(alpha);
			pdExtendedGraphicsState.setNonStrokingAlphaConstant(alpha);
		}
		/*
		 * Try to map the alpha rule into blend modes
		 */
		switch (rule) {
		case AlphaComposite.CLEAR:
			break;
		case AlphaComposite.SRC:
			blendMode = COSName.NORMAL;
			break;
		case AlphaComposite.SRC_OVER:
			blendMode = COSName.COMPATIBLE;
			break;
		case AlphaComposite.XOR:
			blendMode = COSName.EXCLUSION;
			break;
		case AlphaComposite.DST:
			break;
		case AlphaComposite.DST_ATOP:
			break;
		case AlphaComposite.SRC_ATOP:
			blendMode = COSName.COMPATIBLE;
			break;
		case AlphaComposite.DST_IN:
			break;
		case AlphaComposite.DST_OUT:
			break;
		case AlphaComposite.SRC_IN:
			break;
		case AlphaComposite.SRC_OUT:
			break;
		case AlphaComposite.DST_OVER:
			break;
		}
		dictExtendedState.setItem(COSName.BM, blendMode);
	}

	private PDShading buildLinearGradientShading(Paint paint, AffineTransform tf) throws IOException {
		/*
		 * Batik has a copy of RadialGradientPaint, but it has the same structure as the
		 * AWT RadialGradientPaint. So we use Reflection to access the fields of both
		 * these classes.
		 */
		Color[] colors = getPropertyValue(paint, "getColors");
		Color firstColor = colors[0];
		PDColor firstColorMapped = colorMapper.mapColor(contentStream, firstColor);
		applyAsStrokingColor(firstColor);

		PDShadingType3 shading = new PDShadingType3(new COSDictionary());
		shading.setShadingType(PDShading.SHADING_TYPE2);
		shading.setColorSpace(firstColorMapped.getColorSpace());
		float[] fractions = getPropertyValue(paint, "getFractions");
		Point2D startPoint = getPropertyValue(paint, "getStartPoint");
		Point2D endPoint = getPropertyValue(paint, "getEndPoint");
		AffineTransform gradientTransform = getPropertyValue(paint, "getTransform");
		tf.concatenate(gradientTransform);

		tf.transform(startPoint, startPoint);
		tf.transform(endPoint, endPoint);

		COSArray coords = new COSArray();
		coords.add(new COSFloat((float) startPoint.getX()));
		coords.add(new COSFloat((float) startPoint.getY()));
		coords.add(new COSFloat((float) endPoint.getX()));
		coords.add(new COSFloat((float) endPoint.getY()));
		shading.setCoords(coords);

		PDFunctionType3 type3 = buildType3Function(colors, fractions);

		COSArray extend = new COSArray();
		extend.add(COSBoolean.TRUE);
		extend.add(COSBoolean.TRUE);
		shading.setFunction(type3);
		shading.setExtend(extend);
		return shading;
	}

	private PDShading buildRadialGradientShading(Paint paint, AffineTransform tf) throws IOException {
		/*
		 * Batik has a copy of RadialGradientPaint, but it has the same structure as the
		 * AWT RadialGradientPaint. So we use Reflection to access the fields of both
		 * these classes.
		 */
		Color[] colors = getPropertyValue(paint, "getColors");
		Color firstColor = colors[0];
		PDColor firstColorMapped = colorMapper.mapColor(contentStream, firstColor);
		applyAsStrokingColor(firstColor);

		PDShadingType3 shading = new PDShadingType3(new COSDictionary());
		shading.setShadingType(PDShading.SHADING_TYPE3);
		shading.setColorSpace(firstColorMapped.getColorSpace());
		float[] fractions = getPropertyValue(paint, "getFractions");
		Point2D centerPoint = getPropertyValue(paint, "getCenterPoint");
		Point2D focusPoint = getPropertyValue(paint, "getFocusPoint");
		AffineTransform gradientTransform = getPropertyValue(paint, "getTransform");
		tf.concatenate(gradientTransform);
		tf.transform(centerPoint, centerPoint);
		tf.transform(focusPoint, focusPoint);

		@SuppressWarnings("ConstantConditions")
		float radius = getPropertyValue(paint, "getRadius");
		radius = (float) Math.abs(radius * tf.getScaleX());

		COSArray coords = new COSArray();

		coords.add(new COSFloat((float) centerPoint.getX()));
		coords.add(new COSFloat((float) centerPoint.getY()));
		coords.add(new COSFloat(0));
		coords.add(new COSFloat((float) focusPoint.getX()));
		coords.add(new COSFloat((float) focusPoint.getY()));
		coords.add(new COSFloat(radius));
		shading.setCoords(coords);

		PDFunctionType3 type3 = buildType3Function(colors, fractions);

		COSArray extend = new COSArray();
		extend.add(COSBoolean.TRUE);
		extend.add(COSBoolean.TRUE);
		shading.setFunction(type3);
		shading.setExtend(extend);
		return shading;
	}

	private PDShading buildGradientShading(AffineTransform tf, GradientPaint gradientPaint) throws IOException {
		Color[] colors = new Color[] { gradientPaint.getColor1(), gradientPaint.getColor2() };
		Color firstColor = colors[0];
		PDColor firstColorMapped = colorMapper.mapColor(contentStream, firstColor);

		applyAsStrokingColor(firstColor);

		PDShadingType3 shading = new PDShadingType3(new COSDictionary());
		shading.setShadingType(PDShading.SHADING_TYPE2);
		shading.setColorSpace(firstColorMapped.getColorSpace());
		float[] fractions = new float[] { 0, 1 };
		Point2D startPoint = gradientPaint.getPoint1();
		Point2D endPoint = gradientPaint.getPoint2();

		tf.transform(startPoint, startPoint);
		tf.transform(endPoint, endPoint);

		COSArray coords = new COSArray();
		coords.add(new COSFloat((float) startPoint.getX()));
		coords.add(new COSFloat((float) startPoint.getY()));
		coords.add(new COSFloat((float) endPoint.getX()));
		coords.add(new COSFloat((float) endPoint.getY()));
		shading.setCoords(coords);

		PDFunctionType3 type3 = buildType3Function(colors, fractions);

		COSArray extend = new COSArray();
		extend.add(COSBoolean.TRUE);
		extend.add(COSBoolean.TRUE);

		shading.setFunction(type3);
		shading.setExtend(extend);
		return shading;
	}

	private void applyTexturePaint(TexturePaint texturePaint) throws IOException {
		Rectangle2D anchorRect = texturePaint.getAnchorRect();
		PDTilingPattern pattern = new PDTilingPattern();
		pattern.setPaintType(PDTilingPattern.PAINT_COLORED);
		pattern.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING_FASTER_TILING);

		pattern.setBBox(new PDRectangle((float) anchorRect.getX(), (float) anchorRect.getY(),
				(float) anchorRect.getWidth(), (float) anchorRect.getHeight()));
		pattern.setXStep((float) anchorRect.getWidth());
		pattern.setYStep((float) anchorRect.getHeight());

		AffineTransform patternTransform = new AffineTransform();
		patternTransform.translate(0, anchorRect.getHeight());
		patternTransform.scale(1f, -1f);
		pattern.setMatrix(patternTransform);

		PDAppearanceStream appearance = new PDAppearanceStream(document);
		appearance.setResources(pattern.getResources());
		appearance.setBBox(pattern.getBBox());

		PDPageContentStream imageContentStream = new PDPageContentStream(document, appearance,
				((COSStream) pattern.getCOSObject()).createOutputStream());
		BufferedImage texturePaintImage = texturePaint.getImage();
		PDImageXObject imageXObject = imageEncoder.encodeImage(document, imageContentStream, texturePaintImage);

		float ratioW = (float) ((anchorRect.getWidth()) / texturePaintImage.getWidth());
		float ratioH = (float) ((anchorRect.getHeight()) / texturePaintImage.getHeight());
		float paintHeight = (texturePaintImage.getHeight()) * ratioH;
		imageContentStream.drawImage(imageXObject, (float) anchorRect.getX(), (float) (paintHeight + anchorRect.getY()),
				texturePaintImage.getWidth() * ratioW, -paintHeight);
		imageContentStream.close();

		PDColorSpace patternCS1 = new PDPattern(null, imageXObject.getColorSpace());
		COSName tilingPatternName = resources.add(pattern);
		PDColor patternColor = new PDColor(tilingPatternName, patternCS1);

		contentStream.setNonStrokingColor(patternColor);
		contentStream.setStrokingColor(patternColor);
	}

	@SuppressWarnings("WeakerAccess")
	protected PDFunctionType3 buildType3Function(Color[] colors, @SuppressWarnings("unused") float[] fractions) {
		COSDictionary function = new COSDictionary();
		function.setInt(COSName.FUNCTION_TYPE, 3);

		COSArray domain = new COSArray();
		domain.add(new COSFloat(0));
		domain.add(new COSFloat(1));

		COSArray encode = new COSArray();

		COSArray range = new COSArray();
		range.add(new COSFloat(0));
		range.add(new COSFloat(1));
		COSArray bounds = new COSArray();
		for (int i = 2; i < colors.length; i++)
			bounds.add(new COSFloat((1.0f / colors.length) * (i - 1)));

		COSArray functions = buildType2Functions(colors, domain, encode);

		function.setItem(COSName.FUNCTIONS, functions);
		function.setItem(COSName.BOUNDS, bounds);
		function.setItem(COSName.ENCODE, encode);

		PDFunctionType3 type3 = new PDFunctionType3(function);
		type3.setDomainValues(domain);
		return type3;
	}

	@SuppressWarnings("WeakerAccess")
	protected COSArray buildType2Functions(Color[] colors, COSArray domain, COSArray encode) {
		Color prevColor = colors[0];

		COSArray functions = new COSArray();
		for (int i = 1; i < colors.length; i++) {
			Color color = colors[i];
			PDColor prevPdColor = colorMapper.mapColor(contentStream, prevColor);
			PDColor pdColor = colorMapper.mapColor(contentStream, color);
			COSArray c0 = new COSArray();
			COSArray c1 = new COSArray();
			for (float component : prevPdColor.getComponents())
				c0.add(new COSFloat(component));
			for (float component : pdColor.getComponents())
				c1.add(new COSFloat(component));

			COSDictionary type2Function = new COSDictionary();
			type2Function.setInt(COSName.FUNCTION_TYPE, 2);
			type2Function.setItem(COSName.C0, c0);
			type2Function.setItem(COSName.C1, c1);
			type2Function.setInt(COSName.N, 1);
			type2Function.setItem(COSName.DOMAIN, domain);
			functions.add(type2Function);

			encode.add(new COSFloat(0));
			encode.add(new COSFloat(1));
			prevColor = color;
		}
		return functions;
	}

	/**
	 * Get a property value from an object using reflection
	 * 
	 * @param obj
	 *            The object to get a property from.
	 * @param propertyGetter
	 *            method name of the getter, i.e. "getXY".
	 * @return the value read from the object
	 */
	@SuppressWarnings({ "unchecked", "WeakerAccess" })
	protected <T> T getPropertyValue(Object obj, String propertyGetter) {
		try {
			Class c = obj.getClass();
			while (c != null) {
				try {
					Method m = c.getMethod(propertyGetter, (Class<?>[]) null);
					// noinspection JavaReflectionInvocation
					return (T) m.invoke(obj);
				} catch (NoSuchMethodException ignored) {
				}
				c = c.getSuperclass();
			}
			throw new NullPointerException("Method " + propertyGetter + " not found!");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static abstract class COSResourceCacheBase<TObject extends COSObjectable> {
		private Map<Integer, List<TObject>> states = new HashMap<Integer, List<TObject>>();

		private static boolean equalsCOSDictionary(COSDictionary cosDictionary, COSDictionary cosDictionary1) {
			if (cosDictionary.size() != cosDictionary1.size())
				return false;
			for (COSName name : cosDictionary.keySet()) {
				COSBase item = cosDictionary.getItem(name);
				COSBase item2 = cosDictionary1.getItem(name);
				if (!equalsCOSBase(item, item2))
					return false;
			}
			return true;
		}

		private static boolean equalsCOSBase(COSBase item, COSBase item2) {
			if (item == item2)
				return true;
			if (item == null)
				return false;
			if (item2 == null)
				return false;
			/*
			 * Can the items be compared directly?
			 */
			if (item.equals(item2))
				return true;

			if (item instanceof COSDictionary && item2 instanceof COSDictionary)
				return equalsCOSDictionary((COSDictionary) item, (COSDictionary) item2);

			// noinspection SimplifiableIfStatement
			if (item instanceof COSArray && item2 instanceof COSArray)
				return equalsCOSArray((COSArray) item, (COSArray) item2);

			return false;
		}

		private static boolean equalsCOSArray(COSArray item, COSArray item2) {
			if (item.size() != item2.size())
				return false;
			for (int i = 0; i < item.size(); i++) {
				COSBase i1 = item.getObject(i);
				COSBase i2 = item2.getObject(i);
				if (!equalsCOSBase(i1, i2))
					return false;
			}
			return true;
		}

		protected abstract int getKey(TObject obj);

		TObject makeUnqiue(TObject state) {
			int key = getKey(state);
			List<TObject> pdExtendedGraphicsStates = states.get(key);
			if (pdExtendedGraphicsStates == null) {
				pdExtendedGraphicsStates = new ArrayList<TObject>();
				states.put(key, pdExtendedGraphicsStates);
			}
			for (TObject s : pdExtendedGraphicsStates) {
				if (stateEquals(s, state))
					return s;
			}
			pdExtendedGraphicsStates.add(state);
			return state;
		}

		private boolean stateEquals(TObject s, TObject state) {
			COSBase base1 = s.getCOSObject();
			COSBase base2 = state.getCOSObject();
			return equalsCOSBase(base1, base2);
		}
	}

	private static class ExtGStateCache extends COSResourceCacheBase<PDExtendedGraphicsState> {
		@Override
		protected int getKey(PDExtendedGraphicsState obj) {
			return obj.getCOSObject().size();
		}
	}

	private static class PDShadingCache extends COSResourceCacheBase<PDShading> {
		@Override
		protected int getKey(PDShading obj) {
			return obj.getCOSObject().size();
		}
	}

}
