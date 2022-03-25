/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.debug.gui.pcode;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.math.BigInteger;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.swing.*;
import javax.swing.table.*;

import org.apache.commons.lang3.StringUtils;

import docking.action.DockingAction;
import docking.widgets.table.*;
import docking.widgets.table.DefaultEnumeratedColumnTableModel.EnumeratedTableColumn;
import ghidra.GhidraOptions;
import ghidra.app.plugin.core.debug.DebuggerCoordinates;
import ghidra.app.plugin.core.debug.DebuggerPluginPackage;
import ghidra.app.plugin.core.debug.gui.DebuggerResources;
import ghidra.app.plugin.core.debug.gui.pcode.UniqueRow.RefType;
import ghidra.app.plugin.core.debug.service.emulation.DebuggerTracePcodeEmulator;
import ghidra.app.plugin.processors.sleigh.template.OpTpl;
import ghidra.app.services.DebuggerEmulationService;
import ghidra.app.services.DebuggerTraceManagerService;
import ghidra.app.util.pcode.AbstractAppender;
import ghidra.app.util.pcode.AbstractPcodeFormatter;
import ghidra.async.SwingExecutorService;
import ghidra.base.widgets.table.DataTypeTableCellEditor;
import ghidra.docking.settings.Settings;
import ghidra.framework.options.AutoOptions;
import ghidra.framework.options.annotation.*;
import ghidra.framework.plugintool.AutoService;
import ghidra.framework.plugintool.ComponentProviderAdapter;
import ghidra.framework.plugintool.annotation.AutoServiceConsumed;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeExecutorState;
import ghidra.pcode.exec.PcodeFrame;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.data.DataType;
import ghidra.program.model.lang.Language;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;
import ghidra.trace.model.Trace;
import ghidra.trace.model.time.schedule.TraceSchedule;
import ghidra.util.ColorUtils;
import ghidra.util.HTMLUtilities;
import ghidra.util.database.UndoableTransaction;
import ghidra.util.table.GhidraTable;
import ghidra.util.table.GhidraTableFilterPanel;
import ghidra.util.table.column.AbstractGColumnRenderer;

public class DebuggerPcodeStepperProvider extends ComponentProviderAdapter {
	private static final FontRenderContext METRIC_FRC =
		new FontRenderContext(new AffineTransform(), false, false);
	private static final String BACKGROUND_COLOR = "Background Color";

	private static final String ADDRESS_COLOR = "Address Color";
	private static final String REGISTERS_COLOR = "Registers Color";
	private static final String CONSTANT_COLOR = "Constant Color";
	private static final String LABELS_LOCAL_COLOR = "Labels, Local Color";
	private static final String MNEMONIC_COLOR = "Mnemonic Color";
	private static final String UNIMPL_COLOR = "Unimplemented Mnemonic Color";
	private static final String SEPARATOR_COLOR = "Separator Color";
	private static final String LINE_LABEL_COLOR = "P-code Line Label Color";
	private static final String SPACE_COLOR = "P-code Address Space Color";
	private static final String RAW_COLOR = "P-code Raw Varnode Color";
	private static final String USEROP_COLOR = "P-code Userop Color";

	private static final String SPAN_ADDRESS = "addr";
	private static final String SPAN_REGISTER = "reg";
	private static final String SPAN_SCALAR = "scalar";
	private static final String SPAN_LOCAL = "loc";
	private static final String SPAN_MNEMONIC = "op";
	private static final String SPAN_UNIMPL = "unimpl";
	private static final String SPAN_SEPARATOR = "sep";
	private static final String SPAN_LINE_LABEL = "lab";
	private static final String SPAN_SPACE = "space";
	private static final String SPAN_RAW = "raw";
	private static final String SPAN_USEROP = "usr";

	protected static final Comparator<Varnode> UNIQUE_COMPARATOR = (u1, u2) -> {
		assert u1.isUnique() && u2.isUnique();
		return u1.getAddress().compareTo(u2.getAddress());
	};

	protected enum PcodeTableColumns implements EnumeratedTableColumn<PcodeTableColumns, PcodeRow> {
		SEQUENCE("Sequence", Integer.class, PcodeRow::getSequence),
		CODE("Code", String.class, PcodeRow::getCode);

		private final String header;
		private final Function<PcodeRow, ?> getter;
		private final Class<?> cls;

		<T> PcodeTableColumns(String header, Class<T> cls, Function<PcodeRow, T> getter) {
			this.header = header;
			this.cls = cls;
			this.getter = getter;
		}

		@Override
		public String getHeader() {
			return header;
		}

		@Override
		public Class<?> getValueClass() {
			return cls;
		}

		@Override
		public Object getValueOf(PcodeRow row) {
			return getter.apply(row);
		}

		@Override
		public boolean isSortable() {
			return this == SEQUENCE; // HACK
		}
	}

	protected static class PcodeTableModel
			extends DefaultEnumeratedColumnTableModel<PcodeTableColumns, PcodeRow> {
		public PcodeTableModel() {
			super("p-code", PcodeTableColumns.class);
		}

		@Override
		public List<PcodeTableColumns> defaultSortOrder() {
			return List.of(PcodeTableColumns.SEQUENCE);
		}
	}

	protected enum UniqueTableColumns
		implements EnumeratedTableColumn<UniqueTableColumns, UniqueRow> {
		REF("Ref", RefType.class, UniqueRow::getRefType),
		UNIQUE("Unique", String.class, UniqueRow::getName),
		BYTES("Bytes", String.class, UniqueRow::getBytes),
		VALUE("Value", BigInteger.class, UniqueRow::getValue),
		TYPE("Type", DataType.class, UniqueRow::getDataType, UniqueRow::setDataType),
		REPR("Repr", String.class, UniqueRow::getValueRepresentation);

		private final String header;
		private final Function<UniqueRow, ?> getter;
		private final BiConsumer<UniqueRow, Object> setter;
		private final Class<?> cls;

		@SuppressWarnings("unchecked")
		<T> UniqueTableColumns(String header, Class<T> cls, Function<UniqueRow, T> getter,
				BiConsumer<UniqueRow, T> setter) {
			this.header = header;
			this.cls = cls;
			this.getter = getter;
			this.setter = (BiConsumer<UniqueRow, Object>) setter;
		}

		<T> UniqueTableColumns(String header, Class<T> cls, Function<UniqueRow, T> getter) {
			this(header, cls, getter, null);
		}

		@Override
		public Class<?> getValueClass() {
			return cls;
		}

		@Override
		public Object getValueOf(UniqueRow row) {
			return getter.apply(row);
		}

		@Override
		public String getHeader() {
			return header;
		}

		@Override
		public void setValueOf(UniqueRow row, Object value) {
			setter.accept(row, value);
		}

		@Override
		public boolean isEditable(UniqueRow row) {
			return setter != null;
		}
	}

	protected static class UniqueTableModel
			extends DefaultEnumeratedColumnTableModel<UniqueTableColumns, UniqueRow> {
		public UniqueTableModel() {
			super("Unique", UniqueTableColumns.class);
		}

		@Override
		public List<UniqueTableColumns> defaultSortOrder() {
			return List.of(UniqueTableColumns.UNIQUE);
		}
	}

	class UniqueDataTypeEditor extends DataTypeTableCellEditor {
		public UniqueDataTypeEditor() {
			super(plugin.getTool());
		}

		@Override
		protected DataType resolveSelection(DataType dataType) {
			if (dataType == null) {
				return null;
			}
			try (UndoableTransaction tid =
				UndoableTransaction.start(current.getTrace(), "Resolve DataType", true)) {
				return current.getTrace().getDataTypeManager().resolve(dataType, null);
			}
		}
	}

	class CounterBackgroundCellRenderer extends AbstractGColumnRenderer<String> {
		Color foregroundColor = getForeground();

		@Override
		public Component getTableCellRendererComponent(GTableCellRenderingData data) {
			super.getTableCellRendererComponent(data);
			setForeground(pcodeTable.getForeground());
			PcodeRow row = (PcodeRow) data.getRowObject();
			if (data.isSelected()) {
				if (row.isNext()) {
					Color blend = ColorUtils.blend(counterColor, cursorColor, 0.5f);
					if (blend != null) {
						setBackground(blend);
					}
				}
				// else background is already set. Leave it alone
			}
			else if (row.isNext()) {
				setBackground(counterColor);
			}
			else {
				setBackground(pcodeTable.getBackground());
				setOpaque(true);
			}
			setBorder(noFocusBorder);
			return this;
		}

		@Override
		public String getFilterString(String t, Settings settings) {
			return t;
		}
	}

	class PcodeCellRenderer extends CounterBackgroundCellRenderer {
		{
			setHTMLRenderingEnabled(true);
		}

		@Override
		protected void configureFont(JTable table, TableModel model, int column) {
			setFont(fixedWidthFont);
		}

		@Override
		public Component getTableCellRendererComponent(GTableCellRenderingData data) {
			super.getTableCellRendererComponent(data);
			setText(injectStyle(getText()));
			return this;
		}

		String injectStyle(String html) {
			if (StringUtils.startsWithIgnoreCase(html, "<html>")) {
				return style + html.substring("<html>".length());
			}
			return html;
		}
	}

	class UniqueRefCellRenderer extends AbstractGColumnRenderer<RefType> {
		@Override
		public Component getTableCellRendererComponent(GTableCellRenderingData data) {
			super.getTableCellRendererComponent(data);
			setText("");
			switch ((RefType) data.getValue()) {
				case NONE:
					setIcon(null);
					break;
				case READ:
					setIcon(DebuggerResources.ICON_UNIQUE_REF_READ);
					break;
				case WRITE:
					setIcon(DebuggerResources.ICON_UNIQUE_REF_WRITE);
					break;
				case READ_WRITE:
					setIcon(DebuggerResources.ICON_UNIQUE_REF_RW);
					break;
				default:
					throw new AssertionError();
			}
			return this;
		}

		@Override
		public String getFilterString(RefType t, Settings settings) {
			return t.name();
		}
	}

	protected static String htmlSpan(String cls, String display) {
		return String.format("<span class=\"%s\">%s</span>", cls,
			HTMLUtilities.escapeHTML(display));
	}

	class ToPcodeRowsAppender extends AbstractAppender<List<PcodeRow>> {
		private final List<PcodeRow> rows = new ArrayList<>();
		private final PcodeFrame frame;
		private StringBuilder html;
		private PcodeOp op;
		private boolean isNext;

		public ToPcodeRowsAppender(Language language, boolean indent, PcodeFrame frame) {
			super(language, indent);
			this.frame = frame;
		}

		void startRow(PcodeOp op, boolean isNext) {
			this.op = op;
			this.isNext = isNext;
			html = new StringBuilder("<html>");
		}

		void endRow() {
			html.append("</html>");
			rows.add(new OpPcodeRow(language, op, isNext, html.toString()));
		}

		@Override
		public void appendAddressWordOffcut(long wordOffset, long offcut) {
			html.append(htmlSpan(SPAN_ADDRESS, stringifyWordOffcut(wordOffset, offcut)));
		}

		@Override
		public void appendCharacter(char c) {
			if (c == '=') {
				html.append(htmlSpan(SPAN_SEPARATOR, " = "));
			}
			else {
				html.append(htmlSpan(SPAN_SEPARATOR, Character.toString(c)));
			}
		}

		@Override
		public void appendIndent() {
			html.append("&nbsp;&nbsp;");
		}

		@Override
		public void appendLabel(String label) {
			html.append(htmlSpan(SPAN_LOCAL, label));
		}

		@Override
		public void appendLineLabelRef(long label) {
			html.append(htmlSpan(SPAN_LINE_LABEL, stringifyLineLabel(label)));
		}

		@Override
		public void appendMnemonic(int opcode) {
			String style = opcode == PcodeOp.UNIMPLEMENTED ? SPAN_UNIMPL : SPAN_MNEMONIC;
			html.append(htmlSpan(style, stringifyOpMnemonic(opcode)));
		}

		@Override
		public void appendRawVarnode(AddressSpace space, long offset, long size) {
			html.append(htmlSpan(SPAN_RAW, stringifyRawVarnode(space, offset, size)));
		}

		@Override
		public void appendRegister(Register register) {
			html.append(htmlSpan(SPAN_REGISTER, stringifyRegister(register)));
		}

		@Override
		public void appendScalar(long value) {
			html.append(htmlSpan(SPAN_SCALAR, stringifyScalarValue(value)));
		}

		@Override
		public void appendSpace(AddressSpace space) {
			html.append(htmlSpan(SPAN_SPACE, stringifySpace(space)));
		}

		@Override
		public void appendUnique(long offset) {
			html.append(htmlSpan(SPAN_LOCAL, stringifyUnique(offset)));
		}

		@Override
		public void appendUserop(int id) {
			html.append(htmlSpan(SPAN_USEROP, stringifyUserop(language, id)));
		}

		@Override
		protected String stringifyUseropUnchecked(Language language, int id) {
			String name = super.stringifyUseropUnchecked(language, id);
			if (name != null) {
				return name;
			}
			return frame.getUseropName(id);
		}

		@Override
		public List<PcodeRow> finish() {
			return rows;
		}
	}

	class PcodeRowHtmlFormatter
			extends AbstractPcodeFormatter<List<PcodeRow>, ToPcodeRowsAppender> {

		private final Language language;
		private final PcodeFrame frame;
		private int index;
		private int nextRowIndex;

		public PcodeRowHtmlFormatter(Language language, PcodeFrame frame) {
			this.language = language;
			this.frame = frame;
		}

		List<PcodeRow> getRows() {
			return formatOps(language, frame.getCode());
		}

		@Override
		protected ToPcodeRowsAppender createAppender(Language language, boolean indent) {
			return new ToPcodeRowsAppender(language, indent, frame);
		}

		@Override
		public FormatResult formatOpTemplate(ToPcodeRowsAppender appender, OpTpl template) {
			if (isLineLabel(template)) {
				appender.startRow(null, false);
			}
			else {
				PcodeOp op = frame.getCode().get(index++);
				boolean isNext = op.getSeqnum().getTime() == frame.index();
				if (isNext) {
					nextRowIndex = appender.rows.size();
				}
				appender.startRow(op, isNext);
			}
			FormatResult result = super.formatOpTemplate(appender, template);
			appender.endRow();
			return result;
		}
	}

	protected static String createColoredStyle(String cls, Color color) {
		if (color == null) {
			return "";
		}
		return " ." + cls + " { color:" + HTMLUtilities.toHexString(color) + "; }";
	}

	protected static boolean sameCoordinates(DebuggerCoordinates a, DebuggerCoordinates b) {
		if (!Objects.equals(a.getTrace(), b.getTrace())) {
			return false;
		}
		if (!Objects.equals(a.getTime(), b.getTime())) {
			return false;
		}
		if (!Objects.equals(a.getThread(), b.getThread())) {
			return false;
		}
		return true;
	}

	private final DebuggerPcodeStepperPlugin plugin;

	DebuggerCoordinates current = DebuggerCoordinates.NOWHERE;
	DebuggerCoordinates previous = DebuggerCoordinates.NOWHERE;

	@AutoServiceConsumed
	private DebuggerTraceManagerService traceManager;
	@AutoServiceConsumed // NB. also by method
	private DebuggerEmulationService emulationService;
	@SuppressWarnings("unused")
	private AutoService.Wiring autoServiceWiring;

	@AutoOptionDefined(
		name = DebuggerResources.OPTION_NAME_COLORS_PCODE_COUNTER,
		description = "Background color for the current p-code operation",
		help = @HelpInfo(anchor = "colors"))
	private Color counterColor = DebuggerResources.DEFAULT_COLOR_PCODE_COUNTER;

	private Color backgroundColor;
	private Color cursorColor;

	private Color addressColor;
	private Color registerColor;
	private Color scalarColor;
	private Color localColor;
	private Color mnemonicColor;
	private Color unimplColor;
	private Color separatorColor;
	private Color lineLabelColor;
	private Color spaceColor;
	private Color rawColor;
	private Color useropColor;

	@SuppressWarnings("unused")
	private AutoOptions.Wiring autoOptionsWiring;

	String style = "<html>";

	JSplitPane mainPanel = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

	GhidraTable uniqueTable;
	UniqueTableModel uniqueTableModel = new UniqueTableModel();
	GhidraTableFilterPanel<UniqueRow> uniqueFilterPanel;

	GhidraTable pcodeTable;
	PcodeTableModel pcodeTableModel = new PcodeTableModel();
	JLabel instructionLabel;
	// No filter panel on p-code

	DockingAction actionStepBackward;
	DockingAction actionStepForward;

	public DebuggerPcodeStepperProvider(DebuggerPcodeStepperPlugin plugin) {
		super(plugin.getTool(), DebuggerResources.TITLE_PROVIDER_PCODE, plugin.getName(), null);
		this.plugin = plugin;

		this.autoServiceWiring = AutoService.wireServicesConsumed(plugin, this);
		this.autoOptionsWiring = AutoOptions.wireOptions(plugin, this);

		setIcon(DebuggerResources.ICON_PROVIDER_PCODE);
		setHelpLocation(DebuggerResources.HELP_PROVIDER_PCODE);
		setWindowMenuGroup(DebuggerPluginPackage.NAME);

		buildMainPanel();

		createActions();

		setVisible(true);
		contextChanged();
	}

	@AutoOptionConsumed(
		name = DebuggerResources.OPTION_NAME_COLORS_PCODE_COUNTER)
	private void setCounterColor() {
		pcodeTableModel.fireTableDataChanged();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = BACKGROUND_COLOR)
	private void setBackgroundColor(Color backgroundColor) {
		this.backgroundColor = backgroundColor;
		if (pcodeTable != null) {
			pcodeTable.setBackground(backgroundColor);
		}
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_FIELDS,
		name = GhidraOptions.HIGHLIGHT_CURSOR_LINE_COLOR)
	private void setCursorColor(Color cursorColor) {
		this.cursorColor = cursorColor;
		if (pcodeTable != null) {
			pcodeTable.setSelectionBackground(cursorColor);
		}
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = ADDRESS_COLOR)
	private void setAddressColor(Color addressColor) {
		this.addressColor = addressColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = REGISTERS_COLOR)
	private void setRegisterColor(Color registerColor) {
		this.registerColor = registerColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = CONSTANT_COLOR)
	private void setScalarColor(Color scalarColor) {
		this.scalarColor = scalarColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = LABELS_LOCAL_COLOR)
	private void setLocalColor(Color localColor) {
		this.localColor = localColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = MNEMONIC_COLOR)
	private void setMnemonicColor(Color mnemonicColor) {
		this.mnemonicColor = mnemonicColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = UNIMPL_COLOR)
	private void setUnimplColor(Color unimplColor) {
		this.unimplColor = unimplColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = SEPARATOR_COLOR)
	private void setSeparatorColor(Color separatorColor) {
		this.separatorColor = separatorColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = LINE_LABEL_COLOR)
	private void setLineLabelColor(Color lineLabelColor) {
		this.lineLabelColor = lineLabelColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = SPACE_COLOR)
	private void setSpaceColor(Color spaceColor) {
		this.spaceColor = spaceColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = RAW_COLOR)
	private void setRawColor(Color rawColor) {
		this.rawColor = rawColor;
		recomputeStyle();
	}

	@AutoOptionConsumed(
		category = GhidraOptions.CATEGORY_BROWSER_DISPLAY,
		name = USEROP_COLOR)
	private void setUseropColor(Color useropColor) {
		this.useropColor = useropColor;
		recomputeStyle();
	}

	protected void recomputeStyle() {
		StringBuilder sb = new StringBuilder("<html><head><style>");
		sb.append(createColoredStyle(SPAN_ADDRESS, addressColor));
		sb.append(createColoredStyle(SPAN_REGISTER, registerColor));
		sb.append(createColoredStyle(SPAN_SCALAR, scalarColor));
		sb.append(createColoredStyle(SPAN_LOCAL, localColor));
		sb.append(createColoredStyle(SPAN_MNEMONIC, mnemonicColor));
		sb.append(createColoredStyle(SPAN_UNIMPL, unimplColor));
		sb.append(createColoredStyle(SPAN_SEPARATOR, separatorColor));
		sb.append(createColoredStyle(SPAN_LINE_LABEL, lineLabelColor));
		sb.append(createColoredStyle(SPAN_SPACE, spaceColor));
		sb.append(createColoredStyle(SPAN_RAW, rawColor));
		sb.append(createColoredStyle(SPAN_USEROP, useropColor));
		sb.append("</style></head>"); // NB. </html> should already be at end
		style = sb.toString();
		pcodeTableModel.fireTableDataChanged();
	}

	protected int computeSeqColWidth(JLabel renderer) {
		Font font = renderer.getFont();
		Insets insets = renderer.getBorder().getBorderInsets(renderer);
		return (int) font.getStringBounds("00", METRIC_FRC).getWidth() + insets.left + insets.right;
	}

	protected void buildMainPanel() {
		JPanel pcodePanel = new JPanel(new BorderLayout());
		pcodeTable = new GhidraTable(pcodeTableModel);
		pcodePanel.add(new JScrollPane(pcodeTable));
		instructionLabel = new JLabel();
		pcodePanel.add(instructionLabel, BorderLayout.NORTH);
		mainPanel.setLeftComponent(pcodePanel);

		JPanel uniquePanel = new JPanel(new BorderLayout());
		uniqueTable = new GhidraTable(uniqueTableModel);
		uniquePanel.add(new JScrollPane(uniqueTable));
		uniqueFilterPanel = new GhidraTableFilterPanel<>(uniqueTable, uniqueTableModel);
		uniquePanel.add(uniqueFilterPanel, BorderLayout.SOUTH);
		mainPanel.setRightComponent(uniquePanel);

		pcodeTable.setTableHeader(null);
		pcodeTable.setBackground(backgroundColor);
		pcodeTable.setSelectionBackground(cursorColor);
		pcodeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		pcodeTable.getSelectionModel().addListSelectionListener(evt -> {
			if (evt.getValueIsAdjusting()) {
				return;
			}
			uniqueTableModel.fireTableDataChanged();
		});

		TableColumnModel pcodeColModel = pcodeTable.getColumnModel();
		TableColumn seqCol = pcodeColModel.getColumn(PcodeTableColumns.SEQUENCE.ordinal());
		CounterBackgroundCellRenderer seqColRenderer = new CounterBackgroundCellRenderer();
		seqCol.setCellRenderer(seqColRenderer);
		int seqColWidth = computeSeqColWidth(seqColRenderer);
		seqCol.setMinWidth(seqColWidth);
		seqCol.setMaxWidth(seqColWidth);
		TableColumn codeCol = pcodeColModel.getColumn(PcodeTableColumns.CODE.ordinal());
		codeCol.setCellRenderer(new PcodeCellRenderer());
		//codeCol.setPreferredWidth(75);

		TableColumnModel uniqueColModel = uniqueTable.getColumnModel();
		TableColumn refCol = uniqueColModel.getColumn(UniqueTableColumns.REF.ordinal());
		refCol.setCellRenderer(new UniqueRefCellRenderer());
		refCol.setMinWidth(24);
		refCol.setMaxWidth(24);
		TableColumn uniqCol = uniqueColModel.getColumn(UniqueTableColumns.UNIQUE.ordinal());
		uniqCol.setPreferredWidth(45);
		TableColumn bytesCol = uniqueColModel.getColumn(UniqueTableColumns.BYTES.ordinal());
		bytesCol.setCellRenderer(CustomToStringCellRenderer.MONO_OBJECT);
		bytesCol.setPreferredWidth(65);
		TableColumn valCol = uniqueColModel.getColumn(UniqueTableColumns.VALUE.ordinal());
		valCol.setCellRenderer(CustomToStringCellRenderer.MONO_BIG_HEX); // TODO: Changed coloring
		valCol.setPreferredWidth(45);
		TableColumn typeCol = uniqueColModel.getColumn(UniqueTableColumns.TYPE.ordinal());
		typeCol.setCellEditor(new UniqueDataTypeEditor());
		typeCol.setPreferredWidth(45);
		TableColumn reprCol = uniqueColModel.getColumn(UniqueTableColumns.REPR.ordinal());
		reprCol.setPreferredWidth(45);
	}

	protected void createActions() {
		actionStepBackward = DebuggerResources.EmulatePcodeBackwardAction.builder(plugin)
				.enabledWhen(c -> current.getTrace() != null && current.getTime().pTickCount() != 0)
				.onAction(c -> stepBackwardActivated())
				.buildAndInstallLocal(this);
		actionStepForward = DebuggerResources.EmulatePcodeForwardAction.builder(plugin)
				.enabledWhen(
					c -> current.getThread() != null)
				.onAction(c -> stepForwardActivated())
				.buildAndInstallLocal(this);
	}

	private void stepBackwardActivated() {
		if (current.getTrace() == null) {
			return;
		}
		TraceSchedule time = current.getTime().steppedPcodeBackward(1);
		if (time == null) {
			return;
		}
		traceManager.activateTime(time);
	}

	private void stepForwardActivated() {
		if (current.getThread() == null) {
			return;
		}
		TraceSchedule time = current.getTime().steppedPcodeForward(current.getThread(), 1);
		traceManager.activateTime(time);
	}

	@Override
	public JComponent getComponent() {
		return mainPanel;
	}

	public void coordinatesActivated(DebuggerCoordinates coordinates) {
		if (sameCoordinates(current, coordinates)) {
			current = coordinates;
			return;
		}
		previous = current;
		current = coordinates;

		doLoadPcodeFrame();

		setSubTitle(current.getTime().toString());

		contextChanged();
	}

	protected void populateSingleton(PcodeRow row) {
		pcodeTableModel.clear();
		pcodeTableModel.add(row);
		uniqueTableModel.clear();
	}

	protected void populateFromFrame(PcodeFrame frame, PcodeExecutorState<byte[]> state) {
		populatePcode(frame);
		populateUnique(frame, state);
	}

	protected void populatePcode(PcodeFrame frame) {
		Language language = current.getTrace().getBaseLanguage();

		PcodeRowHtmlFormatter formatter = new PcodeRowHtmlFormatter(language, frame);
		List<PcodeRow> toAdd = formatter.getRows();

		if (frame.isBranch()) {
			toAdd.add(new BranchPcodeRow(frame.getCode().size(), frame.getBranched()));
		}
		else if (frame.isFallThrough()) {
			toAdd.add(new FallthroughPcodeRow(frame.getCode().size()));
		}
		pcodeTableModel.clear();
		pcodeTableModel.addAll(toAdd);
		pcodeTable.getSelectionModel()
				.setSelectionInterval(formatter.nextRowIndex, formatter.nextRowIndex);
		pcodeTable.scrollToSelectedRow();
	}

	protected void populateUnique(PcodeFrame frame, PcodeExecutorState<byte[]> state) {
		Language language = current.getTrace().getBaseLanguage();
		// NOTE: They may overlap. I don't think I care.
		Set<Varnode> uniques = new TreeSet<>(UNIQUE_COMPARATOR);
		for (PcodeOp op : frame.getCode()) {
			Varnode out = op.getOutput();
			if (out != null && out.isUnique()) {
				uniques.add(out);
			}
			for (Varnode in : op.getInputs()) {
				if (in.isUnique()) {
					uniques.add(in);
				}
			}
		}
		// TODO: Highlight uniques that the selected op(s) reference
		//       (including overlaps)
		// TODO: Permit modification of unique variables
		List<UniqueRow> toAdd =
			uniques.stream()
					.map(u -> new UniqueRow(this, language, state, u))
					.collect(Collectors.toList());
		uniqueTableModel.clear();
		uniqueTableModel.addAll(toAdd);
	}

	protected void doLoadPcodeFrame() {
		if (instructionLabel != null) {
			instructionLabel.setText("(no instruction)");
		}
		if (emulationService == null) {
			return;
		}
		DebuggerCoordinates current = this.current; // Volatile, also after background
		Trace trace = current.getTrace();
		if (trace == null) {
			return;
		}
		if (current.getThread() == null) {
			populateSingleton(EnumPcodeRow.NO_THREAD);
			return;
		}
		TraceSchedule time = current.getTime();
		if (time.pTickCount() == 0) {
			populateSingleton(EnumPcodeRow.DECODE);
			return;
		}
		DebuggerTracePcodeEmulator emu = emulationService.getCachedEmulator(trace, time);
		if (emu != null) {
			doLoadPcodeFrameFromEmulator(emu);
			return;
		}
		emulationService.backgroundEmulate(trace, time).thenAcceptAsync(__ -> {
			if (current != this.current) {
				return;
			}
			doLoadPcodeFrameFromEmulator(emulationService.getCachedEmulator(trace, time));
		}, SwingExecutorService.INSTANCE);
	}

	protected void doLoadPcodeFrameFromEmulator(DebuggerTracePcodeEmulator emu) {
		PcodeThread<byte[]> thread = emu.getThread(current.getThread().getPath(), false);
		if (thread == null) {
			/**
			 * Happens when focus is on a thread not stepped in the schedule. Stepping it would
			 * create it and decode its first instruction.
			 */
			populateSingleton(EnumPcodeRow.DECODE);
			return;
		}
		Instruction instruction = thread.getInstruction();
		if (instruction == null) {
			instructionLabel.setText("(no instruction)");
		}
		else {
			instructionLabel.setText(instruction.toString());
		}
		PcodeFrame frame = thread.getFrame();
		if (frame == null) {
			/**
			 * Happens when an instruction is completed via p-code stepping, but the next
			 * instruction has not been decoded, yet.
			 */
			populateSingleton(EnumPcodeRow.DECODE);
			return;
		}
		populateFromFrame(frame, thread.getState());
	}

	@AutoServiceConsumed
	private void setEmulationService(DebuggerEmulationService emulationService) {
		doLoadPcodeFrame();
	}
}
