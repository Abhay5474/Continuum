/**
 * The console's second vocabulary: rows, marks and flows.
 *
 * <p>The first vocabulary — {@code Plane}, {@code Readout}, {@code Meter} — is
 * for <em>instrumentation</em>: a value being watched. It is the right thing for
 * a page that reports numbers, and the wrong thing for a page you use to find
 * and configure a capability. Applied there it produced what it always produces:
 * a wall of identical bordered rectangles, each carrying a row of badges,
 * because a card has nowhere to put structure except more chrome.
 *
 * <p>So this is a separate set, built on three ideas.
 *
 * <p><b>A list is a list.</b> Rows separated by a hairline, not boxes separated
 * by margin. A row can be dense because it is not competing with a border on
 * every side, and thirty of them read as one object rather than thirty.
 *
 * <p><b>Kind is carried by a mark, not by a badge.</b> {@code [OCR]} in a pill
 * is the same shape as {@code [FREE TIER]} and {@code [TEXT IN]}, so the eye
 * cannot rank them and reads none. A drawn glyph for "reads a document" is
 * recognised before it is read, and it does not cost a line of text.
 *
 * <p><b>Show the shape of the thing.</b> What a specialist <em>is</em> is a
 * transformation: something goes in, something structured comes out. A sentence
 * describing that is worse than drawing it, so {@link Flow} draws it.
 */
export * from "./marks";
export * from "./tone";
export * from "./glyphs";
export * from "./structure";
export * from "./flow";
export * from "./route";
export * from "./command";
export * from "./detail";
export * from "./workspace";
export * from "./panels";
export * from "./sequence";
