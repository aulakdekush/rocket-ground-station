package uorocketry.basestation;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.border.Border;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.knowm.xchart.XChartPanel;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.XYSeries.XYSeriesRenderStyle;
import org.knowm.xchart.style.Styler.LegendPosition;
import org.knowm.xchart.style.Styler.YAxisPosition;
import org.knowm.xchart.style.XYStyler;

import com.fazecast.jSerialComm.SerialPort;

import uorocketry.basestation.config.Config;
import uorocketry.basestation.connections.DeviceConnection;
import uorocketry.basestation.connections.DeviceConnectionHolder;
import uorocketry.basestation.connections.DataReceiver;
import uorocketry.basestation.control.StateButton;
import uorocketry.basestation.data.DataHolder;
import uorocketry.basestation.data.DataProcessor;
import uorocketry.basestation.data.DataTableCellRenderer;
import uorocketry.basestation.data.DataType;
import uorocketry.basestation.external.GoogleEarthUpdater;
import uorocketry.basestation.external.WebViewUpdater;
import uorocketry.basestation.panel.DataChart;
import uorocketry.basestation.panel.SnapPanel;
import uorocketry.basestation.panel.SnapPanelListener;
import uorocketry.basestation.panel.TableHolder;

public class Main implements ComponentListener, ChangeListener, ActionListener, MouseListener, ListSelectionListener, DataReceiver, SnapPanelListener {
	
	/** Constants */

	/** Data file location for the simulation (new line separated for each event). This does not include the extension/ */
	public static final String SIM_DATA_LOCATION = "data/data";
	public static final String SIM_DATA_EXTENSION = ".txt";
	
	public static final Color LEGEND_BACKGROUND_COLOR = new Color(255, 255, 255, 100);
	
	/** Whether to update Google Earth file */
	public static boolean googleEarth = false;
	/** Where the updating Google Earth kml file is stored */
	public static final String GOOGLE_EARTH_DATA_LOCATION = "data/positions.kml";

	public static Config config;

	/** Used for the map view */
	GoogleEarthUpdater googleEarthUpdater;
	
	/** Whether to update web view JSON file */
	public static boolean webView = false;
	public static int WEBVIEW_PORT = 4534;
	
	/** Used for the web view */
	WebViewUpdater webViewUpdater;
	
	/** Used to limit displayed data points to speed up rendering */
	public static int maxDataPointsDisplayed = 800;
	
	/** Is this running in simulation mode. Must be set at the beginning as it changes the setup. */
	public static boolean simulation = false;
	
	public DataProcessor dataProcessor;
	
	/** Index of the current data point being looked at */
	ArrayList<Integer> currentDataIndexes = new ArrayList<>(2);
	/** Index of the minimum data point being looked at */
	ArrayList<Integer> minDataIndexes = new ArrayList<>(2);
	
	/** If {@link this.currentDataIndexes} should be set to the latest message */
	boolean latest = true;
	/** If true, slider will temporarily stop growing */
	boolean paused = false;
	
	/** Used to only update the UI once at a time, even though it runs in its own thread */
	boolean updatingUI = false;
	
	/** If not in a simulation, the serial ports being listened to */
	DeviceConnectionHolder deviceConnectionHolder = new DeviceConnectionHolder();
	
	public Window window;
	
	/** The chart last clicked */
	DataChart selectedChart;
	Border selectionBorder = BorderFactory.createLineBorder(Color.blue);
	
	/** The width and height of the chart container to resize elements inside on resize. */
	int chartContainerWidth = -1;
	int chartContainerHeight = -1;
	
	/** Set to true when automatically selecting or deselcting from the data table */
	boolean ignoreSelections = false;
	
	/** If true, it will show the latest data instead of showing a subset of all data */
	boolean onlyShowLatestData = false;
	
	/** If true, clicking on data in a chart will hide it */
	public boolean dataDeletionMode = false;
	
	public static void main(String[] args) {
		// Find different possible commands
		for (int i = 0; i + 1 < args.length; i++) {
			switch(args[i]) {
			case "--sim":
				simulation = Boolean.parseBoolean(args[i + 1]);
				
				break;
			}
		}
		
		new Main();
	}
	
	public Main() {
		// Load labels
		config = new Config();
		
		// Create window
		window = new Window(this);
		
		window.addComponentListener(this);
		
		setupUI();
		
		// Different setups depending on if simulation or not
		setupData();

		// Setup Google Earth map support
		if (googleEarth) {
			setupGoogleEarth();
		}
		
		// Setup web view support
		if (webView) {
			setupWebView();
		}
		
		// Update UI once
		updateUI();
	}
	
	private void setupData() {
		dataProcessor = new DataProcessor(config, window.dataTables);

		currentDataIndexes = new ArrayList<>(config.getDataSourceCount());
		minDataIndexes = new ArrayList<>(config.getDataSourceCount());

		for (int i = 0; i < config.getDataSourceCount(); i++) {
			// Add data indexes
			currentDataIndexes.add(0);
			minDataIndexes.add(0);

			// Reset sliders
			window.maxSliders.get(i).setValue(0);
			window.minSliders.get(i).setValue(0);
		}

		// Load simulation data if necessary
		if (simulation) {
			loadSimulationData();

			window.savingToLabel.setText("");
		}

		setupSerialComList();
		setupLogFileName();

		updateUI();
	}

	public void setupSerialComList() {
	    deviceConnectionHolder.setAllSerialPorts(SerialPort.getCommPorts());

		// Make array for the selector
		String[] comSelectorData = new String[deviceConnectionHolder.getAllSerialPorts().length];

		for (int i = 0; i < deviceConnectionHolder.getAllSerialPorts().length; i++) {
			comSelectorData[i] = deviceConnectionHolder.getAllSerialPorts()[i].getDescriptivePortName();
		}

		for (DeviceConnection deviceConnection : deviceConnectionHolder) {
		    deviceConnection.getSelectorList().setListData(comSelectorData);
		}
	}

	public void setupLogFileName() {
		dataProcessor.setupLogFileName();

		window.savingToLabel.setText("Saving to " + dataProcessor.formattedSavingToLocations());
	}

	public void setupUI() {
		addChart();

		// Add slider listeners
		for (int i = 0; i < config.getDataSourceCount(); i++) {
			window.maxSliders.get(i).addChangeListener(this);
			window.minSliders.get(i).addChangeListener(this);
		}

		// Buttons
		window.clearDataButton.addActionListener(this);
		window.refreshComSelectorButton.addActionListener(this);
		window.hideComSelectorButton.addActionListener(this);
		window.hideBarsButton.addActionListener(this);
		window.pauseButton.addActionListener(this);
		window.latestButton.addActionListener(this);

		window.addChartButton.addActionListener(this);

		window.setMaxDataPointsButton.addActionListener(this);

		window.restoreDeletedData.addActionListener(this);

		window.saveLayout.addActionListener(this);
		window.loadLayout.addActionListener(this);

		// Checkboxes
		window.googleEarthCheckBox.addActionListener(this);
		window.webViewCheckBox.addActionListener(this);
		window.simulationCheckBox.addActionListener(this);
		window.onlyShowLatestDataCheckBox.addActionListener(this);
		window.dataDeletionModeCheckBox.addActionListener(this);

		// Set simulation checkbox to be default
		window.simulationCheckBox.setSelected(simulation);

		// Setup listeners for table
		for (TableHolder tableHolder : window.dataTables) {
			tableHolder.getReceivedDataTable().getSelectionModel().addListSelectionListener(this);
			tableHolder.getReceivedDataTable().addMouseListener(this);
		}

		// Setup Snap Panel system
		synchronized (window.charts) {
			selectedChart = window.charts.get(0);
			selectedChart.snapPanel.setSnapPanelListener(this);

			snapPanelSelected(selectedChart.snapPanel);
		}
	}

	public void setupGoogleEarth() {
		googleEarthUpdater = new GoogleEarthUpdater();

		// Setup updater file
//		googleEarthUpdater.createKMLUpdaterFile();
	}

	public void setupWebView() {
		if (webViewUpdater != null) webViewUpdater.close();
		webViewUpdater = new WebViewUpdater();
	}

	public void updateUI() {
		// If not ready yet
		if (dataProcessor== null || dataProcessor.getAllReceivedData().size() == 0 || updatingUI) return;

		updatingUI = true;

		// Update UI on another thread
		new Thread(this::updateUIInternal).start();
	}

	private void updateUIInternal() {
		try {
			// Update every table's data
			for (int i = 0; i < dataProcessor.getAllReceivedData().size(); i++) {
				// If not ready yet
				if (dataProcessor.getAllReceivedData().get(i).size() == 0) continue;

				// Don't change slider if paused
				if (!paused) {
					// Set max value of the sliders
					window.maxSliders.get(i).setMaximum(dataProcessor.getAllReceivedData().get(i).size() - 1);
					window.minSliders.get(i).setMaximum(dataProcessor.getAllReceivedData().get(i).size() - 1);

					// Move position to end
					if (latest) {
						window.maxSliders.get(i).setValue(dataProcessor.getAllReceivedData().get(i).size() - 1);
					}
				}

				DataHolder receivedData = dataProcessor.setTableTo(i, currentDataIndexes.get(i));

				if (window.stateButtons.size() > i && receivedData != null) {
					try {
						int stateIndex = config.getObject().getJSONArray("datasets").getJSONObject(i).getInt("stateIndex");
						for (StateButton stateButton: window.stateButtons.get(i)) {
						    Float value = receivedData.data[stateIndex].getDecimalValue();
						    if (value != null) {
						        stateButton.stateChanged(value.intValue());
						    }
						}
					} catch (JSONException ignored) {}
				}
			}

			if (googleEarth) {
				googleEarthUpdater.updateKMLFile(dataProcessor.getAllReceivedData(), minDataIndexes, currentDataIndexes, config.getObject().getJSONArray("datasets"), false);
			}

			if (webView) {
				webViewUpdater.sendUpdate(dataProcessor.getAllReceivedData(), minDataIndexes, currentDataIndexes, config.getObject().getJSONArray("datasets"));
			}

			// Update every chart
			synchronized (window.charts) {
				for (DataChart chart : window.charts) {
					updateChart(chart);
				}
			}
		} catch (Exception e) {
			// Don't let an exception while updating break the program
			e.printStackTrace();
		}

		updatingUI = false;
	}

	/**
	 * Update the chart with data up to currentDataIndex, and then call window.repaint()
	 *
	 * @param chart The chart to update
	 */
	public void updateChart(DataChart chart) {
		// Update altitude chart
		ArrayList<Float> altitudeDataX = new ArrayList<>();
		ArrayList<ArrayList<Float>> altitudeDataY = new ArrayList<ArrayList<Float>>();

		// Add all array lists
		for (int i = 0; i < chart.xTypes.length; i++) {
			altitudeDataY.add(new ArrayList<Float>());
		}

		// Add y axis
		{
			int maxDataIndex = currentDataIndexes.get(chart.yType.tableIndex);
			int minDataIndex = minDataIndexes.get(chart.yType.tableIndex);
			if (onlyShowLatestData) minDataIndex = Math.max(maxDataIndex - maxDataPointsDisplayed, minDataIndex);

			for (int i = minDataIndex; i <= maxDataIndex; i++) {
				if (dataProcessor.getAllReceivedData().get(chart.yType.tableIndex).size() == 0) continue;

				DataHolder data = dataProcessor.getAllReceivedData().get(chart.yType.tableIndex).get(i);

				DataHolder other = dataProcessor.getAllReceivedData().get(chart.xTypes[0].tableIndex).get(i);

				if (data != null && (other == null || !other.hiddenDataTypes.contains(other.types[chart.xTypes[0].index]))) {
					altitudeDataX.add(data.data[chart.yType.index].getDecimalValue());
				}
			}
		}


		// Add x axis
		for (int i = 0; i < chart.xTypes.length; i++) {
			// Used to limit the max number of data points displayed
			float targetRatio = (float) maxDataPointsDisplayed / (currentDataIndexes.get(chart.xTypes[i].tableIndex) - minDataIndexes.get(chart.xTypes[i].tableIndex));
			int dataPointsAdded = 0;

			int maxDataIndex = currentDataIndexes.get(chart.xTypes[i].tableIndex);
			int minDataIndex = minDataIndexes.get(chart.xTypes[i].tableIndex);
			if (onlyShowLatestData) minDataIndex = Math.max(maxDataIndex - maxDataPointsDisplayed, minDataIndex);

			for (int j = minDataIndex; j <= maxDataIndex; j++) {
				if (dataProcessor.getAllReceivedData().get(chart.yType.tableIndex).size() == 0) continue;

				DataHolder data = dataProcessor.getAllReceivedData().get(chart.xTypes[i].tableIndex).get(j);

				if (data != null) {
					// Ensures that not too many data points are displayed
					// Always show data if only showing latest data (that is handled by changing the minSlider)
					boolean shouldShowDataPoint = onlyShowLatestData || ((float) dataPointsAdded / j <= targetRatio);

					if (!data.hiddenDataTypes.contains(data.types[chart.xTypes[i].index]) && shouldShowDataPoint ) {
						altitudeDataY.get(i).add(data.data[chart.xTypes[i].index].getDecimalValue());

						dataPointsAdded++;
					} else if (!shouldShowDataPoint) {
						// Hidden data
						altitudeDataY.get(i).add(null);
					}
				}
			}
		}

		if (altitudeDataX.size() == 0) {
			// Add default data
			altitudeDataX.add(0f);

			for (int j = 0; j < chart.xTypes.length; j++) {
				altitudeDataY.get(j).add(0f);
			};
		}

		String[] newActiveSeries = new String[chart.xTypes.length];
		StringBuilder title = new StringBuilder();

		// Set Labels
		for (int i = 0; i < chart.xTypes.length; i++) {
			String xTypeTitle = config.getLabel(chart.xTypes[i].tableIndex)[chart.xTypes[i].index];

			if (title.length() != 0) title.append(", ");
			title.append(xTypeTitle);

			chart.xyChart.setYAxisGroupTitle(i, xTypeTitle);

			XYSeries series = null;

			if (chart.activeSeries.length > i) {
				series = chart.xyChart.updateXYSeries("series" + i, altitudeDataX, altitudeDataY.get(i), null);
			} else {
				series = chart.xyChart.addSeries("series" + i, altitudeDataX, altitudeDataY.get(i), null);
			}

			series.setLabel(xTypeTitle);
			series.setYAxisGroup(i);

			newActiveSeries[i] = "series" + i;
		}

		String yTypeTitle = config.getLabel(chart.yType.tableIndex)[chart.yType.index];

		chart.xyChart.setTitle(title + " vs " + yTypeTitle);

		chart.xyChart.setXAxisTitle(yTypeTitle);

		// Remove extra series
		for (int i = chart.xTypes.length; i < chart.activeSeries.length; i++) {
			chart.xyChart.removeSeries("series" + i);
		}

		chart.activeSeries = newActiveSeries;

		window.repaint();
	}

	/**
	 * Run once at the beginning of simulation mode
	 */
	public void loadSimulationData() {
		// Load simulation data
		for (int i = 0; i < config.getDataSourceCount(); i++) {
			loadSimulationData(i, SIM_DATA_LOCATION + i + SIM_DATA_EXTENSION);
		}
	}
	
	public void loadSimulationData(int index, String fileName) {
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(fileName));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return;
		}
		
		try {
			try {
			    String line = null;

			    while ((line = br.readLine()) != null) {
			        // Parse this line and add it as a data point
					dataProcessor.receivedData(index, line.getBytes(StandardCharsets.UTF_8));
			    }
			} finally {
			    br.close();
			}
		} catch(IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Triggered every time the slider changes
	 */
	@Override
	public void stateChanged(ChangeEvent e) {
		if (e.getSource() instanceof JSlider && window.maxSliders.contains(e.getSource())) {
			JSlider maxSlider = (JSlider) e.getSource();
			int tableIndex = window.maxSliders.indexOf(maxSlider);
			
			currentDataIndexes.set(tableIndex, maxSlider.getValue());
			
			// Check if min is too high
			if (minDataIndexes.get(tableIndex) > currentDataIndexes.get(tableIndex)) {
				minDataIndexes.set(tableIndex, currentDataIndexes.get(tableIndex));
				window.minSliders.get(tableIndex).setValue(minDataIndexes.get(tableIndex));
			}
			
			
			updateUI();
		} else if (e.getSource() instanceof JSlider && window.minSliders.contains(e.getSource())) {
			JSlider minSlider = (JSlider) e.getSource();
			int tableIndex = window.minSliders.indexOf(minSlider);

			minDataIndexes.set(tableIndex, minSlider.getValue());
			
			// Check if min is too high
			if (minDataIndexes.get(tableIndex) > currentDataIndexes.get(tableIndex)) {
				minDataIndexes.set(tableIndex, currentDataIndexes.get(tableIndex));
				minSlider.setValue(minDataIndexes.get(tableIndex));
			}
			
			updateUI();
		}
	}

	@Override
	public void receivedData(DeviceConnection deviceConnection, byte[] data) {
		dataProcessor.receivedData(deviceConnection, data);
        
        updateUI();
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() == window.clearDataButton) {
			if (JOptionPane.showConfirmDialog(window, 
					"Are you sure you would like to clear all the data?") == 0) {
				for (int i = 0; i < dataProcessor.getAllReceivedData().size(); i++) {
					dataProcessor.getAllReceivedData().get(i).clear();
				}
				
				updateUI();
			}
		} else if (e.getSource() == window.refreshComSelectorButton) {
            setupSerialComList();
        }  else if (e.getSource() == window.hideComSelectorButton) {
			window.comPanelParent.setVisible(!window.comPanelParent.isVisible());
			
			if (window.comPanelParent.isVisible()) {
				window.hideComSelectorButton.setText("Hide Com Selector");
			} else {
				window.hideComSelectorButton.setText("Show Com Selector");
			}
		} else if (e.getSource() == window.hideBarsButton) {
			window.sliderTabs.setVisible(!window.sliderTabs.isVisible());
			
			if (window.sliderTabs.isVisible()) {
				window.hideBarsButton.setText("Hide Sliders");
			} else {
				window.hideBarsButton.setText("Show Sliders");
			}
		} else if (e.getSource() == window.pauseButton) {
			paused = !paused;
			
			if (paused) {
				window.pauseButton.setText("Resume");
			} else {
				window.pauseButton.setText("Pause");
			}
			
		} else if (e.getSource() == window.latestButton) {
			latest = !latest;
			
			if (latest) {
				window.latestButton.setText("Detach From Latest");
				
				for (int i = 0; i < window.maxSliders.size(); i++) {
					window.maxSliders.get(i).setValue(dataProcessor.getAllReceivedData().get(0).size() - 1);
				}
			} else {
				window.latestButton.setText("Latest");
			}
		} else if (e.getSource() == window.addChartButton) {
			addChart();
		} else if (e.getSource() == window.googleEarthCheckBox) {
			googleEarth = window.googleEarthCheckBox.isSelected();
			
			if (googleEarth) setupGoogleEarth();
		} else if (e.getSource() == window.webViewCheckBox) {
			webView = window.webViewCheckBox.isSelected();
			
			if (webView) setupWebView();
		} else if (e.getSource() == window.simulationCheckBox && window.simulationCheckBox.isSelected() != simulation) {
			String warningMessage = "";
			if (window.simulationCheckBox.isSelected()) {
				warningMessage = "Are you sure you would like to enable simulation mode?\n\n"
						+ "The current data will be deleted from the UI. You can find it in " + dataProcessor.formattedSavingToLocations();
			} else {
				warningMessage = "Are you sure you would like to disable simulation mode?";
			}
			
			if (JOptionPane.showConfirmDialog(window, warningMessage) == 0) {
				simulation = window.simulationCheckBox.isSelected();
				
				setupData();
			} else {
				window.simulationCheckBox.setSelected(simulation);
			}
		} else if (e.getSource() == window.onlyShowLatestDataCheckBox) {
			onlyShowLatestData = window.onlyShowLatestDataCheckBox.isSelected();
		} else if (e.getSource() == window.setMaxDataPointsButton) {
			try {
				maxDataPointsDisplayed = Integer.parseInt(window.maxDataPointsTextField.getText());
			} catch (NumberFormatException ignored) {}
		} else if (e.getSource() == window.dataDeletionModeCheckBox) {
			dataDeletionMode = window.dataDeletionModeCheckBox.isSelected();
		} else if (e.getSource() == window.restoreDeletedData) {
			for (List<DataHolder> dataHolders : dataProcessor.getAllReceivedData()) {
				for (DataHolder dataHolder : dataHolders) {
					// See if the hidden list needs to be cleared
					if (dataHolder != null && !dataHolder.hiddenDataTypes.isEmpty()) {
						dataHolder.hiddenDataTypes.clear();
					}
				}
			}
			
			updateUI();
		} else if (e.getSource() == window.saveLayout) {
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setAcceptAllFileFilterUsed(false);
			fileChooser.addChoosableFileFilter(new LayoutFileFilter());
			fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			
			// Start the in current working directory
			fileChooser.setCurrentDirectory(new File("."));

			int result = fileChooser.showSaveDialog(window);
			
			if (result == JFileChooser.APPROVE_OPTION) {
				File saveFile = fileChooser.getSelectedFile();
				
				// Add extension
				if (!saveFile.getName().endsWith(".rlay")) {
					saveFile = new File(saveFile.getPath() + ".rlay");
				}
				
				// Prep file
				JSONObject saveObject = new JSONObject();
				
				JSONArray chartsArray = new JSONArray();
				saveObject.put("charts", chartsArray);
				
				for (DataChart chart: window.charts) {
					JSONObject chartData = new JSONObject();
					
					chartData.put("x", chart.snapPanel.relX);
					chartData.put("y", chart.snapPanel.relY);
					chartData.put("width", chart.snapPanel.relWidth);
					chartData.put("height", chart.snapPanel.relHeight);
					
					// Add xTypes
					JSONArray xTypeArray = new JSONArray();
					for (DataType dataType: chart.xTypes) {
						JSONObject xTypeData = new JSONObject();
						
						xTypeData.put("index", dataType.index);
						xTypeData.put("tableIndex", dataType.tableIndex);
						
						xTypeArray.put(xTypeData);
					}
					chartData.put("xTypes", xTypeArray);
					
					// Add yType
					JSONObject yTypeData = new JSONObject();
					yTypeData.put("index", chart.yType.index);
					yTypeData.put("tableIndex", chart.yType.tableIndex);
					chartData.put("yType", yTypeData);
					
					chartsArray.put(chartData);
				}
				
				// Save file
				try (PrintWriter out = new PrintWriter(saveFile)) {
				    out.println(saveObject.toString());
				} catch (FileNotFoundException e1) {
					e1.printStackTrace();
				}
			}
			
		} else if (e.getSource() == window.loadLayout) {
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setAcceptAllFileFilterUsed(false);
			fileChooser.addChoosableFileFilter(new LayoutFileFilter());
			fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			
			// Start the in current working directory
			fileChooser.setCurrentDirectory(new File("."));

			int result = fileChooser.showOpenDialog(window);
			
			if (result == JFileChooser.APPROVE_OPTION) {
				File saveFile = fileChooser.getSelectedFile();
				
				// Load file
				JSONObject loadedLayout = null;
				try {
					loadedLayout = new JSONObject(new String(Files.readAllBytes(saveFile.toPath()), StandardCharsets.UTF_8));
				} catch (JSONException | IOException e1) {
					e1.printStackTrace();
				}

				JSONArray chartsArray = loadedLayout.getJSONArray("charts");
				
				// Clear current charts
				for (DataChart dataChart: window.charts) {
					// Remove from the UI
					window.centerChartPanel.remove(dataChart.chartPanel);
				}
				
				// Finally, remove it from the list
				window.charts.clear();
				
				for (int i = 0; i < chartsArray.length(); i++) {
					JSONObject chartData = chartsArray.getJSONObject(i);
					
					addChart(true);
					
					DataChart chart = window.charts.get(i);
					
					// Get location
					chart.snapPanel.relX = chartData.getDouble("x");
					chart.snapPanel.relY = chartData.getDouble("y");
					chart.snapPanel.relWidth = chartData.getDouble("width");
					chart.snapPanel.relHeight = chartData.getDouble("height");
					
					chart.snapPanel.updateBounds(window.centerChartPanel.getWidth(), window.centerChartPanel.getHeight());
					
					// Get xTypes
					JSONArray xTypeArray = chartData.getJSONArray("xTypes");
					chart.xTypes = new DataType[xTypeArray.length()];
					for (int j = 0; j < chart.xTypes.length; j++) {
						JSONObject xTypeData = xTypeArray.getJSONObject(j);
						
						chart.xTypes[j] = new DataType(xTypeData.getInt("index"), xTypeData.getInt("tableIndex"));
					}
					
					// Add yType
					JSONObject yTypeData = chartData.getJSONObject("yType");
					chart.yType = new DataType(yTypeData.getInt("index"), yTypeData.getInt("tableIndex"));
				}
				
				updateUI();
			}
		}
	}
	
	public void addChart() {
		addChart(false);
	}
	
	/**
	 * @param silent Will not perform tasks such as updating the UI or selecting the chart
	 */
	public void addChart(boolean silent) {
		XYChart xyChart = new XYChartBuilder().title("Altitude vs Timestamp (s)").xAxisTitle("Timestamp (s)").yAxisTitle("Altitude (m)").build();

		// Customize Chart
		XYStyler firstChartStyler = xyChart.getStyler();
		
		firstChartStyler.setLegendPosition(LegendPosition.InsideNE);
		firstChartStyler.setLegendVisible(true);
		firstChartStyler.setLegendBackgroundColor(LEGEND_BACKGROUND_COLOR);
		firstChartStyler.setToolTipsEnabled(true);
		firstChartStyler.setDefaultSeriesRenderStyle(XYSeriesRenderStyle.Scatter);
		//noinspection SuspiciousNameCombination
		firstChartStyler.setYAxisGroupPosition(1, YAxisPosition.Right);

		// Series
		xyChart.addSeries("series0", new double[] { 0 }, new double[] { 0 });
		
		XChartPanel<XYChart> chartPanel = new XChartPanel<>(xyChart);
		window.centerChartPanel.add(chartPanel);
		
		DataChart dataChart = new DataChart(this, xyChart, chartPanel);
		
		// Set default size
		dataChart.snapPanel.setRelSize(600, 450);
		
		// Add these default charts to the list
		synchronized (window.charts) {
			window.charts.add(dataChart);
		}
		
		// Set to be selected
		window.centerChartPanel.setComponentZOrder(chartPanel, 0);
		dataChart.snapPanel.setSnapPanelListener(this);
		
		if (selectedChart != null) selectedChart.chartPanel.setBorder(null);
		
		if (!silent) {
			selectedChart = dataChart;
			
			snapPanelSelected(selectedChart.snapPanel);
			
			updateUI();
		}
	}

	/** For com selector JList */
	@Override
	public void valueChanged(ListSelectionEvent e) {
		if(e.getSource() instanceof ListSelectionModel && !ignoreSelections) {
			for (int i = 0; i < window.dataTables.size(); i++) {
				JTable dataTable = window.dataTables.get(i).getReceivedDataTable();
				
				if (e.getSource() == dataTable.getSelectionModel()) {
					int[] selections = dataTable.getSelectedRows();
					DataType[] formattedSelections = new DataType[selections.length];
					
					moveSelectionsToNewTable(i, true);
					
					for (int j = 0; j < formattedSelections.length; j++) {
						formattedSelections[j] = new DataType(selections[j], window.dataTables.indexOf(dataTable));
					}
					
					synchronized (window.charts) {
						// Set chart to be based on this row
						selectedChart.xTypes = formattedSelections;
					}
					
					dataTable.setColumnSelectionInterval(0, 0);
					
					updateUI();
					
					break;
				}
			}
		}
	}
	
	@Override
	public void mousePressed(MouseEvent e) {
		for (int i = 0; i < window.dataTables.size(); i++) {
			JTable dataTable = window.dataTables.get(i).getReceivedDataTable();
			
			if (e.getSource() == dataTable && e.getButton() == MouseEvent.BUTTON3) {
				// Left clicking the dataTable
				int row = dataTable.rowAtPoint(e.getPoint());

				ignoreSelections = true;
				
				moveSelectionsToNewTable(i, false);
				
				selectedChart.yType = new DataType(row, i);
				
				((DataTableCellRenderer) dataTable.getDefaultRenderer(Object.class)).coloredRow = row;
				dataTable.repaint();
				
				updateUI();
				
				ignoreSelections = false;
			}
		}
	}
	
	public void moveSelectionsToNewTable(int newTableIndex, boolean changingX) {
		boolean movingXType = false;
		
		// Clear previous selections
		for (int j = 0; j < selectedChart.xTypes.length; j++) {
			if (selectedChart.xTypes[j].tableIndex != newTableIndex) {
				int currentTableIndex = selectedChart.xTypes[j].tableIndex;
				
				// Clear that table's selection
				window.dataTables.get(currentTableIndex).getReceivedDataTable().clearSelection();
				window.dataTables.get(currentTableIndex).getReceivedDataTable().repaint();
				
				movingXType = true;
			}
		}
		
		if (movingXType && !changingX) {
			selectedChart.xTypes = new DataType[1];
			selectedChart.xTypes[0] = new DataType(1, newTableIndex);
			
			window.dataTables.get(newTableIndex).getReceivedDataTable().setRowSelectionInterval(1, 1);
			window.dataTables.get(newTableIndex).getReceivedDataTable().setColumnSelectionInterval(0, 0);
			window.dataTables.get(newTableIndex).getReceivedDataTable().repaint();
		}
		
		// Move yType selection if needed
		if (selectedChart.yType.tableIndex != newTableIndex) {
			// Deselect the old one
			JTable oldDataTable = window.dataTables.get(selectedChart.yType.tableIndex).getReceivedDataTable();
			((DataTableCellRenderer) oldDataTable.getDefaultRenderer(Object.class)).coloredRow = -1;
			oldDataTable.repaint();
			
			// Select this default selection
			JTable dataTable = window.dataTables.get(newTableIndex).getReceivedDataTable();
			((DataTableCellRenderer) dataTable.getDefaultRenderer(Object.class)).coloredRow = 0;
			dataTable.repaint();
			
			selectedChart.yType = new DataType(0, newTableIndex);
		}
	}
	
	/**
	 * Called when a new snap window is highlighted
	 */
	@Override
	public void snapPanelSelected(SnapPanel snapPanel) {
		if (snapPanel.chart != null) {
			// Remove border on old object
			selectedChart.chartPanel.setBorder(null);

			selectedChart = snapPanel.chart;
			
			// Add border
			selectedChart.chartPanel.setBorder(selectionBorder);
			
			// Add selections
			ignoreSelections = true;
			
			for (int i = 0; i < window.dataTables.size(); i++) {
				JTable dataTable = window.dataTables.get(i).getReceivedDataTable();
				
				dataTable.clearSelection();
				for (int j = 0; j < selectedChart.xTypes.length; j++) {
					if (selectedChart.xTypes[j].tableIndex == i) {
						dataTable.addRowSelectionInterval(selectedChart.xTypes[j].index, selectedChart.xTypes[j].index);
					}
				}
				
				// Update yType
				if (selectedChart.yType.tableIndex == i) {
					((DataTableCellRenderer) dataTable.getDefaultRenderer(Object.class)).coloredRow = selectedChart.yType.index;
				}
				
				window.repaint();
				
				dataTable.setColumnSelectionInterval(0, 0);
			}
			
			ignoreSelections = false;
		}
	}

	@Override
	public void componentHidden(ComponentEvent e) {
		
	}

	@Override
	public void componentMoved(ComponentEvent e) {
		
	}

	@Override
	public void componentResized(ComponentEvent e) {
		// Chart Container resize management
		int currentChartContainerWidth = window.centerChartPanel.getWidth();
		int currentChartContainerHeight = window.centerChartPanel.getHeight();
		
		synchronized (window.charts) {
			for (DataChart chart : window.charts) {
				chart.snapPanel.containerResized(currentChartContainerWidth, currentChartContainerHeight);
			}
		}
		
		chartContainerWidth = currentChartContainerWidth;
		chartContainerHeight = currentChartContainerHeight;
	}

	@Override
	public void componentShown(ComponentEvent e) {
		
	}

	@Override
	public void mouseClicked(MouseEvent e) {
		
	}

	@Override
	public void mouseEntered(MouseEvent e) {
		
	}

	@Override
	public void mouseExited(MouseEvent e) {
		
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		
	}
}

class LayoutFileFilter extends javax.swing.filechooser.FileFilter {

	@Override
	public boolean accept(File pathname) {
		if (pathname.isDirectory()) {
			return true;
		} else {
			return pathname.getName().endsWith(".rlay");
		}
	}

	@Override
	public String getDescription() {
		return "Rocket Layout File (.rlay)";
	}
	
}
