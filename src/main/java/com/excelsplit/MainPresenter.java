package com.excelsplit;

import javax.swing.SwingWorker;
import java.awt.Desktop;
import java.nio.file.*;
import java.util.List;

/**
 * Presenter (MVP).
 * Koordiniert View, Service und Config – enthält keine UI-Logik.
 */
public class MainPresenter {

    private final MainWindow          view;
    private final ExcelSplitService service;
    private final AppConfig         config;
    private final Path              basePath;

    public MainPresenter(MainWindow view, ExcelSplitService service, AppConfig config, Path basePath) {
        this.view     = view;
        this.service  = service;
        this.config   = config;
        this.basePath = basePath;

        initView();
        registerHandlers();
    }

    private void initView() {
        Path masterDir = Paths.get(config.getMasterDir(basePath.resolve("master").toString()));
        view.setOutputDir(config.getOutputDir(basePath.resolve("templates").toString()));
        refreshMasterFiles(masterDir);
    }

    private void registerHandlers() {
        view.onMasterDirSelected(dir -> {
            config.setMasterDir(dir.toString());
            refreshMasterFiles(dir);
        });

        view.onOutputDirSelected(config::setOutputDir);

        view.onRefreshRequested(() -> {
            Path dir = Paths.get(config.getMasterDir(basePath.resolve("master").toString()));
            refreshMasterFiles(dir);
        });

        view.onProcessRequested(this::process);
    }

    private void refreshMasterFiles(Path masterDir) {
        view.setMasterFiles(masterDir, service.listMasterFiles(masterDir));
    }

    private void process() {
        List<Path> selected = view.getSelectedFiles();
        if (selected.isEmpty()) {
            view.showWarning("Bitte mindestens eine Masterdatei auswählen.");
            return;
        }

        String outText = view.getOutputDir().trim();
        if (outText.isEmpty()) {
            view.showWarning("Bitte ein Ausgabeverzeichnis angeben.");
            return;
        }

        config.setOutputDir(outText);
        view.clearLog();
        view.setProcessingActive(true);

        Path outputDir = Paths.get(outText);

        new SwingWorker<Path, String>() {
            @Override
            protected Path doInBackground() {
                return service.processFiles(selected, outputDir, this::publish);
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(view::appendLog);
            }

            @Override
            protected void done() {
                view.setProcessingActive(false);
                try {
                    get();
                    if (Files.isDirectory(outputDir)) {
                        Desktop.getDesktop().open(outputDir.toFile());
                    }
                } catch (Exception ignored) { }
            }
        }.execute();
    }
}
