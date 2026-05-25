/**
 * TypeScript types for the Penpot API v2.14
 * Generated from https://design.penpot.app/api/main/doc/openapi.json
 */
declare const _uuid: unique symbol;
declare const _hexColor: unique symbol;
declare const _instant: unique symbol;
declare const _tokenName: unique symbol;
/** UUID string */
export type Uuid = string & {
    readonly [_uuid]: never;
};
/** Hex color string, e.g. "#ff0000" */
export type HexColor = string & {
    readonly [_hexColor]: never;
};
/** ISO-8601 date-time string */
export type Instant = string & {
    readonly [_instant]: never;
};
/** Design token name, matches pattern: ^[a-zA-Z0-9_-][a-zA-Z0-9$_-]*(\.[a-zA-Z0-9$_-]+)*$ */
export type TokenName = string & {
    readonly [_tokenName]: never;
};
/** Known feature flags; additional values are possible */
export type Feature = 'plugins/runtime' | 'styles/v2' | 'fdata/pointer-map' | 'fdata/objects-map' | 'text-editor/v2-html-paste' | 'components/v2' | string;
export type Features = Feature[];
export interface GeomRect {
    x: number;
    y: number;
    width: number;
    height: number;
}
export interface GeomPoint {
    x: number;
    y: number;
}
/** Affine 2D transformation matrix — CSS matrix(a, b, c, d, e, f) */
export interface GeomMatrix {
    a: number;
    b: number;
    c: number;
    d: number;
    e: number;
    f: number;
}
/** Arbitrary key-value data indexed by plugin namespace */
export type PluginsData = Record<string, Record<string, string>>;
export interface GradientStop {
    color: HexColor;
    opacity: number;
    offset: number;
}
export interface Gradient {
    type: 'radial' | 'linear';
    startX: number;
    startY: number;
    endX: number;
    endY: number;
    width: number;
    /** At least one stop required */
    stops: [GradientStop, ...GradientStop[]];
}
/** Reference to an image used as a color fill/stroke */
export interface ImageColor {
    width: number;
    height: number;
    /** MIME type */
    mtype: string;
    id: Uuid;
    name?: string;
    keepAspectRatio?: boolean;
}
export interface GridColor {
    color: HexColor;
    opacity: number;
}
/** Resolved color used for shadows and similar plain-color slots */
export interface PlainColor {
    color: HexColor;
    opacity?: number;
    /** Library color reference ID */
    refId?: Uuid;
    /** Library file reference */
    refFile?: Uuid;
}
export interface LibraryColor {
    id: Uuid;
    name: string;
    path?: string;
    opacity?: number;
    modifiedAt?: Instant;
    pluginData?: PluginsData;
    color?: HexColor;
    gradient?: Gradient;
    image?: ImageColor;
}
export interface FillAttrs {
    fillColorRefFile?: Uuid;
    fillColorRefId?: Uuid;
    fillOpacity?: number;
    fillColor?: HexColor;
    fillColorGradient?: Gradient;
    fillImage?: ImageColor;
}
export type Fill = FillAttrs;
export type StrokeStyle = 'solid' | 'mixed' | 'dashed' | 'dotted';
export type StrokeAlignment = 'center' | 'inner' | 'outer';
export type StrokeCap = null | 'line-arrow' | 'diamond-marker' | 'round' | 'triangle-arrow' | 'square' | 'circle-marker' | 'square-marker';
export interface StrokeAttrs {
    strokeColorRefFile?: Uuid;
    strokeColorRefId?: Uuid;
    strokeOpacity?: number;
    strokeStyle?: StrokeStyle;
    strokeWidth?: number;
    strokeAlignment?: StrokeAlignment;
    strokeCapStart?: StrokeCap;
    strokeCapEnd?: StrokeCap;
    strokeColor?: HexColor;
    strokeColorGradient?: Gradient;
    strokeImage?: ImageColor;
}
export type Stroke = StrokeAttrs;
export type ShadowStyle = 'drop-shadow' | 'inner-shadow';
export interface Shadow {
    id: Uuid | null;
    style: ShadowStyle;
    offsetX: number;
    offsetY: number;
    blur: number;
    spread: number;
    hidden: boolean;
    color: PlainColor;
}
export interface Blur {
    id?: Uuid;
    type: string;
    expand?: number;
    value?: number;
    hidden: boolean;
}
export type ExportFormat = 'png' | 'pdf' | 'webp' | 'svg' | 'jpeg';
export interface ShapeExport {
    type: ExportFormat;
    scale: number;
    suffix: string;
}
export type BlendMode = 'normal' | 'multiply' | 'screen' | 'overlay' | 'darken' | 'lighten' | 'color-dodge' | 'color-burn' | 'hard-light' | 'soft-light' | 'difference' | 'exclusion' | 'hue' | 'saturation' | 'color' | 'luminosity';
export type ColumnGridAlignment = 'center' | 'right' | 'stretch' | 'left';
export interface ColumnGridParams {
    color: GridColor;
    type?: ColumnGridAlignment;
    size?: number | null;
    margin?: number | null;
    itemLength?: number | null;
    gutter?: number | null;
}
export interface ColumnGridAttrs {
    type: 'column';
    display: boolean;
    params: ColumnGridParams;
}
export interface RowGridParams {
    color: GridColor;
    type?: ColumnGridAlignment;
    size?: number | null;
    margin?: number | null;
    itemLength?: number | null;
    gutter?: number | null;
}
export interface RowGridAttrs {
    type: 'row';
    display: boolean;
    params: RowGridParams;
}
export interface SquareGridParams {
    size?: number | null;
    color: GridColor;
}
export interface SquareGridAttrs {
    type: 'square';
    display: boolean;
    params: SquareGridParams;
}
export type Grid = ColumnGridAttrs | RowGridAttrs | SquareGridAttrs;
export type ActionType = 'navigate' | 'close-overlay' | 'open-overlay' | 'prev-screen' | 'toggle-overlay' | 'open-url';
export type EventType = 'click' | 'mouse-press' | 'mouse-over' | 'mouse-enter' | 'mouse-leave' | 'after-delay';
interface BaseInteraction {
    eventType: EventType;
    delay?: number;
}
export interface NavigateInteraction extends BaseInteraction {
    actionType: 'navigate';
    destination?: Uuid | null;
    preserveScrollPosition?: boolean;
    animation?: InteractionAnimation | null;
}
export interface OpenOverlayInteraction extends BaseInteraction {
    actionType: 'open-overlay';
    destination?: Uuid | null;
    overlayPos?: GeomPoint;
    overlayPosType?: string;
    animation?: InteractionAnimation | null;
}
export interface CloseOverlayInteraction extends BaseInteraction {
    actionType: 'close-overlay';
    destination?: Uuid | null;
    animation?: InteractionAnimation | null;
}
export interface ToggleOverlayInteraction extends BaseInteraction {
    actionType: 'toggle-overlay';
    destination?: Uuid | null;
    overlayPos?: GeomPoint;
    overlayPosType?: string;
    animation?: InteractionAnimation | null;
}
export interface PrevScreenInteraction extends BaseInteraction {
    actionType: 'prev-screen';
    animation?: InteractionAnimation | null;
}
export interface OpenUrlInteraction extends BaseInteraction {
    actionType: 'open-url';
    url: string;
}
export type Interaction = NavigateInteraction | OpenOverlayInteraction | CloseOverlayInteraction | ToggleOverlayInteraction | PrevScreenInteraction | OpenUrlInteraction;
export interface InteractionAnimation {
    type: 'dissolve' | 'slide' | 'push';
    duration?: number;
    easing?: string;
    direction?: string;
}
export type LayoutItemSizing = 'fill' | 'fix' | 'auto';
export type LayoutItemAlignSelf = 'start' | 'center' | 'end' | 'stretch';
export type LayoutItemMarginType = 'simple' | 'multiple';
export interface LayoutItemMargin {
    m1: number;
    m2: number;
    m3: number;
    m4: number;
}
export type FlexDirection = 'row' | 'column' | 'row-reverse' | 'column-reverse';
export type FlexAlign = 'start' | 'center' | 'end' | 'stretch' | 'space-between' | 'space-around' | 'space-evenly';
export type FlexWrap = 'wrap' | 'no-wrap' | 'nowrap';
export type LayoutPaddingType = 'simple' | 'multiple';
export type LayoutType = 'flex' | 'grid';
export interface LayoutPadding {
    p1: number;
    p2: number;
    p3: number;
    p4: number;
}
export type GridTrackType = 'fixed' | 'percent' | 'flex' | 'auto';
export interface GridTrack {
    type: GridTrackType;
    value?: number;
}
export type GridCellPosition = 'manual' | 'area' | 'auto';
export type GridCellAlign = 'start' | 'center' | 'end' | 'stretch' | 'auto';
export interface GridCell {
    id: Uuid;
    areaName?: string;
    row: number;
    rowSpan: number;
    column: number;
    columnSpan: number;
    position?: GridCellPosition;
    alignSelf?: GridCellAlign;
    justifySelf?: GridCellAlign;
    shapes: Uuid[];
}
export interface DimensionsTokenAttrs {
    /** Border radius top-left */
    r1?: TokenName;
    /** Border radius top-right */
    r2?: TokenName;
    /** Border radius bottom-right */
    r3?: TokenName;
    /** Border radius bottom-left */
    r4?: TokenName;
    fill?: TokenName;
    strokeColor?: TokenName;
    shadow?: TokenName;
    width?: TokenName;
    height?: TokenName;
    layoutItemMinW?: TokenName;
    layoutItemMaxW?: TokenName;
    layoutItemMinH?: TokenName;
    layoutItemMaxH?: TokenName;
    rowGap?: TokenName;
    columnGap?: TokenName;
    p1?: TokenName;
    p2?: TokenName;
    p3?: TokenName;
    p4?: TokenName;
    m1?: TokenName;
    m2?: TokenName;
    m3?: TokenName;
    m4?: TokenName;
    rotation?: TokenName;
    lineHeight?: TokenName;
    fontSize?: TokenName;
    letterSpacing?: TokenName;
    fontFamily?: TokenName;
    textCase?: TokenName;
    textDecoration?: TokenName;
    strokeWidth?: TokenName;
    fontWeight?: TokenName;
    opacity?: TokenName;
    typography?: TokenName;
    x?: TokenName;
    y?: TokenName;
}
export type ShapeType = 'path' | 'group' | 'frame' | 'circle' | 'svg-raw' | 'image' | 'bool' | 'rect' | 'text';
export type ConstraintsH = 'scale' | 'center' | 'right' | 'leftright' | 'left';
export type ConstraintsV = 'bottom' | 'scale' | 'top' | 'center' | 'topbottom';
export type GrowType = 'auto-height' | 'fixed' | 'auto-width';
export type BoolType = 'exclude' | 'intersection' | 'difference' | 'union';
/** Properties that all shapes may carry, regardless of type */
export interface ShapeCommon {
    id: Uuid;
    name: string;
    type: ShapeType;
    selrect: GeomRect;
    points: GeomPoint[];
    transform: GeomMatrix;
    transformInverse: GeomMatrix;
    parentId: Uuid;
    frameId: Uuid;
    x?: number;
    y?: number;
    width?: number;
    height?: number;
    fills?: Fill[] | null;
    strokes?: Stroke[];
    shadow?: Shadow[];
    blur?: Blur;
    blendMode?: BlendMode;
    opacity?: number;
    exports?: ShapeExport[];
    proportion?: number;
    proportionLock?: boolean;
    constraintsH?: ConstraintsH;
    constraintsV?: ConstraintsV;
    fixedScroll?: boolean;
    rotation?: number;
    r1?: number;
    r2?: number;
    r3?: number;
    r4?: number;
    blocked?: boolean;
    collapsed?: boolean;
    locked?: boolean;
    hidden?: boolean;
    maskedGroup?: boolean;
    pageId?: Uuid;
    componentId?: Uuid;
    componentFile?: Uuid;
    componentRoot?: boolean;
    mainInstance?: boolean;
    remoteSynced?: boolean;
    shapeRef?: Uuid;
    touched?: string[] | null;
    layoutItemMarginType?: LayoutItemMarginType;
    layoutItemMargin?: LayoutItemMargin;
    layoutItemMaxH?: number;
    layoutItemMinH?: number;
    layoutItemMaxW?: number;
    layoutItemMinW?: number;
    layoutItemHSizing?: LayoutItemSizing;
    layoutItemVSizing?: LayoutItemSizing;
    layoutItemAlignSelf?: LayoutItemAlignSelf;
    layoutItemAbsolute?: boolean;
    layoutItemZIndex?: number;
    variantId?: Uuid;
    variantName?: string;
    variantError?: string;
    isVariantContainer?: boolean;
    appliedTokens?: DimensionsTokenAttrs;
    pluginData?: PluginsData;
    interactions?: Interaction[];
    grids?: Grid[];
}
/** Arbitrary SVG path content string */
export type PathContent = string;
export interface PathShape extends ShapeCommon {
    type: 'path';
    /** SVG path data string */
    content: PathContent;
}
export interface GroupShape extends ShapeCommon {
    type: 'group';
    x: number;
    y: number;
    width: number;
    height: number;
    /** Ordered list of child shape IDs */
    shapes: Uuid[];
}
export interface FrameShape extends ShapeCommon {
    type: 'frame';
    x: number;
    y: number;
    width: number;
    height: number;
    /** Ordered list of direct child shape IDs */
    shapes: Uuid[];
    layoutType?: LayoutType;
    layoutFlexDir?: FlexDirection;
    layoutAlignContent?: FlexAlign;
    layoutAlignItems?: FlexAlign;
    layoutJustifyContent?: FlexAlign;
    layoutJustifyItems?: FlexAlign;
    layoutWrapType?: FlexWrap;
    layoutPaddingType?: LayoutPaddingType;
    layoutPadding?: LayoutPadding;
    layoutRowGap?: number;
    layoutColumnGap?: number;
    layoutGridRows?: GridTrack[];
    layoutGridColumns?: GridTrack[];
    layoutGridCells?: Record<string, GridCell>;
    hideFillOnExport?: boolean;
    showContent?: boolean;
    hideInViewer?: boolean;
    clipContent?: boolean;
}
export interface CircleShape extends ShapeCommon {
    type: 'circle';
    x: number;
    y: number;
    width: number;
    height: number;
}
export interface SvgRawContentNode {
    tag: string;
    attrs?: Record<string, string | number>;
    content?: Array<SvgRawContentNode | string>;
}
export interface SvgRawShape extends ShapeCommon {
    type: 'svg-raw';
    /**
     * SVG content. May be a raw markup string, or Penpot's parsed SVG tree node
     * (`{ tag, attrs, content }`) — the form delivered by `get-page`.
     */
    content: string | SvgRawContentNode;
    x?: number;
    y?: number;
    width?: number;
    height?: number;
}
export interface ImageMetadata {
    width: number;
    height: number;
    /** MIME type */
    mtype: string;
    id: Uuid;
    name?: string;
    keepAspectRatio?: boolean;
}
export interface ImageShape extends ShapeCommon {
    type: 'image';
    metadata: ImageMetadata;
    x: number;
    y: number;
    width: number;
    height: number;
}
export interface BoolShape extends ShapeCommon {
    type: 'bool';
    boolType: BoolType;
    /** Child shape IDs that form the operands */
    shapes: Uuid[];
    /** Resulting flattened SVG path */
    content: string;
}
export interface RectShape extends ShapeCommon {
    type: 'rect';
    x: number;
    y: number;
    width: number;
    height: number;
}
export interface TextInlineStyle {
    fontId?: string;
    fontFamily?: string;
    fontSize?: string;
    fontStyle?: string;
    fontWeight?: string;
    lineHeight?: string;
    letterSpacing?: string;
    direction?: string;
    textDecoration?: string;
    textTransform?: string;
    textAlign?: string;
    typographyRefId?: Uuid | null;
    typographyRefFile?: Uuid | null;
}
/** Leaf text node (actual characters) */
export interface TextLeaf extends TextInlineStyle {
    text: string;
    key?: string;
    fills?: Fill[] | null;
}
/** A single paragraph */
export interface ParagraphNode extends TextInlineStyle {
    type: 'paragraph';
    key?: string;
    fills?: Fill[] | null;
    children: [TextLeaf, ...TextLeaf[]];
}
/** A set of paragraphs */
export interface ParagraphSetNode {
    type: 'paragraph-set';
    key?: string;
    children: [ParagraphNode, ...ParagraphNode[]];
}
/** Root of the rich-text tree */
export interface TextContent {
    type: 'root';
    key?: string;
    verticalAlign?: 'top' | 'center' | 'bottom';
    children: [ParagraphSetNode, ...ParagraphSetNode[]];
}
/** Per-glyph position data (used for rendering / hit-testing) */
export interface TextPositionData extends TextInlineStyle {
    x: number;
    y: number;
    width: number;
    height: number;
    fills?: Fill[];
    rtl?: boolean;
    text?: string;
}
export interface TextShape extends ShapeCommon {
    type: 'text';
    content: TextContent | null;
    positionData?: TextPositionData[] | null;
    growType?: GrowType;
    x?: number;
    y?: number;
    width?: number;
    height?: number;
}
export type Shape = PathShape | GroupShape | FrameShape | CircleShape | SvgRawShape | ImageShape | BoolShape | RectShape | TextShape;
export interface PageOptions {
    background?: HexColor;
    savedVersion?: number;
    /** Ruler guides */
    guides?: PageGuide[];
}
export interface PageGuide {
    id: Uuid;
    position: number;
    type: 'horizontal' | 'vertical';
    frameId?: Uuid | null;
}
export interface Page {
    id: Uuid;
    name: string;
    /** All shapes keyed by their UUID */
    objects: Record<string, Shape>;
    options?: PageOptions;
}
export interface Typography {
    id: Uuid;
    name: string;
    fontId: string;
    fontFamily: string;
    fontVariantId: string;
    fontSize: string;
    fontWeight: string;
    fontStyle: string;
    lineHeight: string;
    letterSpacing: string;
    textTransform: string;
    modifiedAt?: Instant;
    path?: string | null;
    pluginData?: PluginsData;
}
export interface Component {
    id: Uuid;
    name: string;
    path?: string;
    mainInstancePage?: Uuid | null;
    mainInstanceId?: Uuid | null;
    annotation?: string | null;
    deleted?: boolean;
    pluginData?: PluginsData;
}
export interface FileMedia {
    id: Uuid;
    createdAt?: Instant;
    deletedAt?: Instant | null;
    name: string;
    width: number;
    height: number;
    mtype: string;
    mediaId: Uuid;
    fileId: Uuid;
}
export interface FileData {
    pages: Uuid[];
    pagesIndex: Record<string, Page>;
    components?: Record<string, Component>;
    colors?: Record<string, LibraryColor>;
    typographies?: Record<string, Typography>;
    media?: Record<string, FileMedia>;
    /** Design tokens library (opaque — structure is internal) */
    tokensLib?: unknown | null;
}
export interface FilePermissions {
    type: string;
    isOwner: boolean;
    isAdmin: boolean;
    canEdit: boolean;
    isRegistered: boolean;
    isPersisted: boolean;
}
export interface PenpotFile {
    id: Uuid;
    features: Features;
    hasMediaTrimmed: boolean;
    commentThreadSeqn: number;
    name: string;
    revn: number;
    vern: number;
    modifiedAt: Instant;
    isShared: boolean;
    projectId: Uuid;
    createdAt: Instant;
    data?: FileData;
    permissions?: FilePermissions;
}
/** Lightweight file info without full page/shape data */
export type PartialFile = Omit<PenpotFile, 'data'> & {
    data?: Partial<FileData>;
};
export interface FileSnapshot {
    id: Uuid;
    fileId: Uuid;
    label?: string | null;
    revn: number;
    createdAt: Instant;
    isLocked?: boolean;
}
export interface FileThumbnail {
    fileId: Uuid;
    revn: number;
    data: string;
}
export interface Project {
    id: Uuid;
    teamId: Uuid;
    name: string;
    isDefault: boolean;
    createdAt: Instant;
    modifiedAt: Instant;
    isPinned?: boolean;
}
export type TeamRole = 'owner' | 'admin' | 'editor' | 'viewer';
export interface Team {
    id: Uuid;
    name: string;
    photo?: string;
    isDefault: boolean;
    features?: Record<string, boolean>;
    createdAt: Instant;
    modifiedAt: Instant;
    permissions?: FilePermissions;
}
export interface TeamMember {
    id: Uuid;
    teamId: Uuid;
    profileId: Uuid;
    role: TeamRole;
    createdAt: Instant;
    modifiedAt: Instant;
    email?: string;
    fullname?: string;
    photo?: string;
}
export interface TeamInvitation {
    id: Uuid;
    teamId: Uuid;
    email: string;
    role: TeamRole;
    createdAt: Instant;
    expiresAt?: Instant;
}
export interface TeamStats {
    usedFileSizeBytes: number;
    limitFileSizeBytes: number | null;
    usedColors: number;
    limitColors: number | null;
    usedComponents: number;
    limitComponents: number | null;
    usedFonts: number;
    limitFonts: number | null;
}
export interface Profile {
    id: Uuid;
    fullname: string;
    email: string;
    isActive: boolean;
    isBlocked: boolean;
    isDemo: boolean;
    isMuted: boolean;
    createdAt: Instant;
    modifiedAt: Instant;
    photo?: string;
    lang?: string;
    theme?: string;
    isAdmin?: boolean;
    props?: Record<string, unknown>;
}
export interface AccessToken {
    id: Uuid;
    name: string;
    createdAt: Instant;
    expiresAt?: Instant | null;
}
export interface CommentThread {
    id: Uuid;
    fileId: Uuid;
    pageId: Uuid;
    seqn: number;
    position: GeomPoint;
    createdAt: Instant;
    modifiedAt: Instant;
    resolvedAt?: Instant | null;
    ownerId: Uuid;
    frameId?: Uuid | null;
    count: number;
    unreadCount?: number;
}
export interface Comment {
    id: Uuid;
    threadId: Uuid;
    profileId: Uuid;
    createdAt: Instant;
    modifiedAt: Instant;
    content: string;
}
export interface ShareLink {
    id: Uuid;
    fileId: Uuid;
    pageId?: Uuid;
    flags: string[];
    createdAt: Instant;
}
export interface FontVariant {
    id: Uuid;
    fontId: string;
    fontFamily: string;
    fontWeight: string;
    fontStyle: string;
    externalFileId?: Uuid;
    createdAt?: Instant;
    modifiedAt?: Instant;
    status?: string;
}
export interface Webhook {
    id: Uuid;
    teamId: Uuid;
    uri: string;
    isActive: boolean;
    mutedTill?: Instant | null;
    errorCode?: string | null;
    errorCount: number;
    createdAt: Instant;
    updatedAt: Instant;
}
export interface WebhookEvent<TProps = unknown> {
    id: Uuid;
    name: string;
    props: TProps;
    profileId: Uuid;
}
export interface AddObjectsChange {
    type: 'add-objects';
    pageId?: Uuid;
    componentId?: Uuid;
    ignoreTouched?: boolean;
    shapes: Shape[];
    index?: number | null;
    afterShape?: Uuid | null;
}
export interface DelObjectsChange {
    type: 'del-objects';
    pageId?: Uuid;
    componentId?: Uuid;
    ignoreTouched?: boolean;
    shapes: Uuid[];
}
export interface ModObjectsChange {
    type: 'mod-objects';
    pageId?: Uuid;
    componentId?: Uuid;
    ignoreTouched?: boolean;
    operations: ShapeOperation[];
}
export interface MovObjectsChange {
    type: 'mov-objects';
    pageId?: Uuid;
    componentId?: Uuid;
    ignoreTouched?: boolean;
    parentId: Uuid;
    shapes: Uuid[];
    index?: number | null;
    afterShape?: Uuid | null;
    allowAlteringCopies?: boolean;
}
export interface ReorderChildrenChange {
    type: 'reorder-children';
    pageId?: Uuid;
    componentId?: Uuid;
    ignoreTouched?: boolean;
    parentId: Uuid;
    shapes: Uuid[];
}
export interface AddPageChange {
    type: 'add-page';
    id?: Uuid;
    name?: string;
    page?: Partial<Page>;
}
export interface ModPageChange {
    type: 'mod-page';
    id: Uuid;
    background?: HexColor | null;
    name?: string;
}
export interface DelPageChange {
    type: 'del-page';
    id: Uuid;
}
export interface MovPageChange {
    type: 'mov-page';
    id: Uuid;
    index: number;
}
export interface RegObjectsChange {
    type: 'reg-objects';
    pageId?: Uuid;
    componentId?: Uuid;
    shapes: Uuid[];
}
export interface AddColorChange {
    type: 'add-color';
    color: LibraryColor;
}
export interface ModColorChange {
    type: 'mod-color';
    color: LibraryColor;
}
export interface DelColorChange {
    type: 'del-color';
    id: Uuid;
}
export interface AddMediaChange {
    type: 'add-media';
    object: FileMedia;
}
export interface DelMediaChange {
    type: 'del-media';
    id: Uuid;
}
export interface AddTypographyChange {
    type: 'add-typography';
    typography: Typography;
}
export interface ModTypographyChange {
    type: 'mod-typography';
    typography: Typography;
}
export interface DelTypographyChange {
    type: 'del-typography';
    id: Uuid;
}
export interface SetPluginDataChange {
    type: 'set-plugin-data';
    objectType: 'color' | 'file' | 'page' | 'component' | 'shape' | 'typography';
    objectId?: Uuid;
    pageId?: Uuid;
    namespace: string;
    key: string;
    value: string | null;
}
export interface SetTokensLibChange {
    type: 'set-tokens-lib';
    tokensLib: unknown | null;
}
export interface AddComponentChange {
    type: 'add-component';
    id: Uuid;
    name: string;
    path?: string;
    shapes?: Shape[];
    pageId?: Uuid;
    mainInstanceId?: Uuid;
    mainInstancePage?: Uuid;
    annotation?: string;
}
export interface ModComponentChange {
    type: 'mod-component';
    id: Uuid;
    name?: string;
    path?: string;
    annotation?: string;
}
export interface DelComponentChange {
    type: 'del-component';
    id: Uuid;
    skipUndelete?: boolean;
}
export interface RestoredComponentChange {
    type: 'restore-component';
    id: Uuid;
}
export type FileChange = AddObjectsChange | DelObjectsChange | ModObjectsChange | MovObjectsChange | ReorderChildrenChange | AddPageChange | ModPageChange | DelPageChange | MovPageChange | RegObjectsChange | AddColorChange | ModColorChange | DelColorChange | AddMediaChange | DelMediaChange | AddTypographyChange | ModTypographyChange | DelTypographyChange | SetPluginDataChange | SetTokensLibChange | AddComponentChange | ModComponentChange | DelComponentChange | RestoredComponentChange;
export type ShapeOperationType = 'set' | 'set-touched' | 'set-remote-synced';
export interface SetShapeOperation {
    type: 'set';
    attr: keyof Shape;
    val: unknown;
    ignore?: boolean;
}
export interface SetTouchedOperation {
    type: 'set-touched';
    touched: string[] | null;
}
export interface SetRemoteSyncedOperation {
    type: 'set-remote-synced';
    val: boolean;
}
export type ShapeOperation = SetShapeOperation | SetTouchedOperation | SetRemoteSyncedOperation;
export interface UpdateFileRequest {
    id: Uuid;
    sessionId?: Uuid;
    revn?: number;
    changes: FileChange[];
}
export interface SsoProvider {
    name: string;
    icon?: string;
    uri: string;
}
export {};
