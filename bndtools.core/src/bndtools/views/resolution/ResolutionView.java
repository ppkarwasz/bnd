package bndtools.views.resolution;

import java.io.File;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bndtools.core.ui.icons.Icons;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.OpenEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DragSourceListener;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.resource.Capability;
import org.osgi.resource.Resource;
import org.osgi.service.repository.Repository;

import aQute.bnd.build.model.EE;
import aQute.bnd.osgi.Clazz;
import aQute.bnd.osgi.resource.ResourceUtils;
import aQute.bnd.unmodifiable.Sets;
import aQute.lib.io.IO;
import aQute.lib.strings.Strings;
import bndtools.Plugin;
import bndtools.model.repo.RepositoryResourceElement;
import bndtools.model.resolution.CapReqMapContentProvider;
import bndtools.model.resolution.CapabilityLabelProvider;
import bndtools.model.resolution.RequirementWrapper;
import bndtools.model.resolution.RequirementWrapperLabelProvider;
import bndtools.tasks.AnalyseBundleResolutionJob;
import bndtools.tasks.BndBuilderCapReqLoader;
import bndtools.tasks.BndFileCapReqLoader;
import bndtools.tasks.CapReqLoader;
import bndtools.tasks.JarFileCapReqLoader;
import bndtools.tasks.ResourceCapReqLoader;
import bndtools.utils.PartAdapter;
import bndtools.utils.SelectionUtils;

public class ResolutionView extends ViewPart implements ISelectionListener, IResourceChangeListener {

	private final List<EE>		ees			= Arrays.asList(EE.values());
	private Display				display		= null;

	private Tree				reqsTree	= null;
	private Table				capsTable	= null;

	private TreeViewer			reqsViewer;
	private TableViewer			capsViewer;

	private ViewerFilter		hideSelfImportsFilter;

	private boolean				inputLocked	= false;
	private boolean				outOfDate	= false;
	Set<CapReqLoader>			loaders;
	private Job					analysisJob;
	private int					currentEE	= 4;

	private final Set<String>	filteredCapabilityNamespaces;

	public ResolutionView() {
		filteredCapabilityNamespaces = Sets.of(IdentityNamespace.IDENTITY_NAMESPACE, HostNamespace.HOST_NAMESPACE);
		loaders = Collections.emptySet();
	}

	private final IPartListener partAdapter = new PartAdapter() {
		@Override
		public void partActivated(IWorkbenchPart part) {
			if (part == ResolutionView.this) {
				if (outOfDate) {
					executeAnalysis();
				}
			} else if (part instanceof IEditorPart) {
				IEditorInput editorInput = ((IEditorPart) part).getEditorInput();
				IFile file = ResourceUtil.getFile(editorInput);
				if (file != null) {
					CapReqLoader loader = getLoaderForFile(file.getLocation()
						.toFile());
					if (loader != null) {
						setLoaders(Collections.singleton(loader));
						if (getSite().getPage()
							.isPartVisible(ResolutionView.this)) {
							executeAnalysis();
						} else {
							outOfDate = true;
						}
					}
				}
			}
		}
	};

	private boolean setLoaders(Set<CapReqLoader> newLoaders) {
		Set<CapReqLoader> oldLoaders = loaders;
		boolean swap = !oldLoaders.equals(newLoaders);
		if (swap) {
			loaders = newLoaders;
		}
		for (CapReqLoader l : swap ? oldLoaders : newLoaders) {
			IO.close(l);
		}
		return swap;
	}

	private CapReqLoader getLoaderForFile(File file) {
		CapReqLoader loader;
		if (Strings.endsWithIgnoreCase(file.getName(), ".bnd")) {
			loader = new BndFileCapReqLoader(file);
		} else if (Strings.endsWithIgnoreCase(file.getName(), ".jar")) {
			loader = new JarFileCapReqLoader(file);
		} else {
			loader = null;
		}
		return loader;
	}

	@Override
	public void createPartControl(Composite parent) {
		this.display = parent.getDisplay();

		SashForm splitPanel = new SashForm(parent, SWT.HORIZONTAL);
		splitPanel.setLayout(new FillLayout());

		Composite reqsPanel = new Composite(splitPanel, SWT.NONE);
		reqsPanel.setBackground(parent.getBackground());

		GridLayout reqsLayout = new GridLayout(1, false);
		reqsLayout.marginWidth = 0;
		reqsLayout.marginHeight = 0;
		reqsLayout.verticalSpacing = 2;
		reqsPanel.setLayout(reqsLayout);
		new Label(reqsPanel, SWT.NONE).setText("Requirements:");
		reqsTree = new Tree(reqsPanel, SWT.FULL_SELECTION | SWT.MULTI | SWT.BORDER);
		reqsTree.setHeaderVisible(false);
		reqsTree.setLinesVisible(false);
		reqsTree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		reqsViewer = new TreeViewer(reqsTree);
		ColumnViewerToolTipSupport.enableFor(reqsViewer);
		reqsViewer.setLabelProvider(new RequirementWrapperLabelProvider(true));
		reqsViewer.setContentProvider(new CapReqMapContentProvider());

		Composite capsPanel = new Composite(splitPanel, SWT.NONE);
		capsPanel.setBackground(parent.getBackground());

		GridLayout capsLayout = new GridLayout(1, false);
		capsLayout.marginWidth = 0;
		capsLayout.marginHeight = 0;
		capsLayout.verticalSpacing = 2;
		capsPanel.setLayout(capsLayout);
		new Label(capsPanel, SWT.NONE).setText("Capabilities:");
		capsTable = new Table(capsPanel, SWT.FULL_SELECTION | SWT.MULTI | SWT.BORDER);
		capsTable.setHeaderVisible(false);
		capsTable.setLinesVisible(false);
		capsTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		capsViewer = new TableViewer(capsTable);
		ColumnViewerToolTipSupport.enableFor(capsViewer);
		capsViewer.setLabelProvider(new CapabilityLabelProvider(true));
		capsViewer.setContentProvider(new CapReqMapContentProvider());
		capsViewer.setFilters(new ViewerFilter() {
			@Override
			public boolean select(Viewer viewer, Object parent, Object element) {
				return !filteredCapabilityNamespaces.contains(((Capability) element).getNamespace());
			}
		});

		hideSelfImportsFilter = new ViewerFilter() {

			@Override
			public boolean select(Viewer viewer, Object parentElement, Object element) {
				if (element instanceof RequirementWrapper) {
					RequirementWrapper rw = (RequirementWrapper) element;
					boolean resolved = rw.resolved | rw.java;
					return !resolved;
				}
				return true;
			}
		};
		reqsViewer.setFilters(hideSelfImportsFilter);

		reqsViewer.addDragSupport(DND.DROP_MOVE | DND.DROP_COPY, new Transfer[] {
			LocalSelectionTransfer.getTransfer()
		}, new LocalTransferDragListener(reqsViewer));

		capsViewer.addDragSupport(DND.DROP_MOVE | DND.DROP_COPY, new Transfer[] {
			LocalSelectionTransfer.getTransfer()
		}, new LocalTransferDragListener(capsViewer));

		reqsViewer.addOpenListener(this::openEditor);

		fillActionBars();

		getSite().getPage()
			.addPostSelectionListener(this);
		getSite().getPage()
			.addPartListener(partAdapter);
		ResourcesPlugin.getWorkspace()
			.addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);

		// Current selection & part
		IWorkbenchPart activePart = getSite().getPage()
			.getActivePart();
		ISelection activeSelection = getSite().getWorkbenchWindow()
			.getSelectionService()
			.getSelection();
		selectionChanged(activePart, activeSelection);
	}

	private void openEditor(OpenEvent event) {
		IStructuredSelection selection = (IStructuredSelection) event.getSelection();
		for (Iterator<?> iter = selection.iterator(); iter.hasNext();) {
			Object item = iter.next();
			if (item instanceof Clazz) {
				Clazz clazz = (Clazz) item;
				String className = clazz.getFQN();
				IType type = null;
				if (!loaders.isEmpty()) {
					IWorkspaceRoot wsroot = ResourcesPlugin.getWorkspace()
						.getRoot();
					for (CapReqLoader loader : loaders) {
						if (loader instanceof BndBuilderCapReqLoader) {
							File loaderFile = ((BndBuilderCapReqLoader) loader).getFile();
							IFile[] wsfiles = wsroot.findFilesForLocationURI(loaderFile.toURI());
							for (IFile wsfile : wsfiles) {
								IJavaProject javaProject = JavaCore.create(wsfile.getProject());
								try {
									type = javaProject.findType(className);
									if (type != null)
										break;
								} catch (JavaModelException e1) {
									ErrorDialog.openError(getSite().getShell(), "Error", "",
										new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0,
											MessageFormat.format("Error opening Java class '{0}'.", className), e1));
								}
							}
						}

					}
				}
				try {
					if (type != null)
						JavaUI.openInEditor(type, true, true);
				} catch (PartInitException e2) {
					ErrorDialog.openError(getSite().getShell(), "Error", "", new Status(IStatus.ERROR, Plugin.PLUGIN_ID,
						0, MessageFormat.format("Error opening Java editor for class '{0}'.", className), e2));
				} catch (JavaModelException e3) {
					ErrorDialog.openError(getSite().getShell(), "Error", "", new Status(IStatus.ERROR, Plugin.PLUGIN_ID,
						0, MessageFormat.format("Error opening Java class '{0}'.", className), e3));
				}
			}
		}
	}

	void fillActionBars() {
		IToolBarManager toolBarManager = getViewSite().getActionBars()
			.getToolBarManager();

		IAction toggleShowSelfImports = new Action("showSelfImports", IAction.AS_CHECK_BOX) {
			@Override
			public void runWithEvent(Event event) {
				if (isChecked()) {
					reqsViewer.removeFilter(hideSelfImportsFilter);
				} else {
					reqsViewer.addFilter(hideSelfImportsFilter);
				}
			}
		};
		toggleShowSelfImports.setChecked(false);
		toggleShowSelfImports.setImageDescriptor(Icons.desc("/icons/package_folder_impexp.gif"));
		toggleShowSelfImports.setToolTipText(
			"Show resolved requirements.\n\nInclude requirements that are resolved within the set of selected bundles.");
		toolBarManager.add(toggleShowSelfImports);

		IAction toggleLockInput = new Action("lockInput", IAction.AS_CHECK_BOX) {
			@Override
			public void runWithEvent(Event event) {
				inputLocked = isChecked();
				if (!inputLocked) {
					executeAnalysis();
				}
			}
		};
		toggleLockInput.setChecked(false);
		toggleLockInput.setImageDescriptor(Icons.desc("lock"));
		toggleLockInput.setToolTipText("Lock to current selection");
		toolBarManager.add(toggleLockInput);

		doEEActionMenu(toolBarManager);
	}

	private void doEEActionMenu(IToolBarManager toolBarManager) {
		MenuManager menuManager = new MenuManager("Java", "resolutionview.java.menu");

		Action showMenuAction = new Action("Java") {
			@Override
			public void runWithEvent(Event event) {
				Menu menu = menuManager.createContextMenu(getViewSite().getShell());
				MenuItem[] items = menu.getItems();
				if (items != null && items.length == ees.size()) {
					menu.setDefaultItem(items[currentEE]);
				}
				Point location = getViewSite().getShell()
					.getDisplay()
					.getCursorLocation();
				menu.setLocation(location.x, location.y);
				menu.setVisible(true);
			}

			@Override
			public ImageDescriptor getImageDescriptor() {
				return Icons.desc("java");
			}
		};
		for (int n = 0; n < ees.size(); n++) {
			int nn = n;
			EE ee = ees.get(n);
			if (ee.getRelease() == 9) {
				currentEE = n;
			}
			String name = getEEName(ee);
			Action action = new Action(name) {
				int index = nn;

				@Override
				public void run() {
					setEE(index);
					showMenuAction.setToolTipText(getEEName(ees.get(currentEE)));
				}
			};
			menuManager.add(action);
		}
		showMenuAction.setToolTipText(getEEName(ees.get(currentEE)));

		toolBarManager.add(showMenuAction);
	}

	private String getEEName(EE ee) {
		return ee == EE.UNKNOWN ? "unknown" : ee.getEEName();
	}

	protected void setEE(int ee) {
		currentEE = ee;
		executeAnalysis();
	}

	@Override
	public void setFocus() {}

	@Override
	public void dispose() {
		getSite().getPage()
			.removeSelectionListener(this);
		ResourcesPlugin.getWorkspace()
			.removeResourceChangeListener(this);
		getSite().getPage()
			.removePartListener(partAdapter);
		setLoaders(Collections.<CapReqLoader> emptySet());
		super.dispose();
	}

	public void setInput(Set<CapReqLoader> sourceLoaders, Map<String, List<Capability>> capabilities,
		Map<String, List<RequirementWrapper>> requirements) {
		setLoaders(sourceLoaders);
		sourceLoaders = loaders;
		if (reqsTree != null && !reqsTree.isDisposed() && capsTable != null && !capsTable.isDisposed()) {
			reqsViewer.setInput(requirements);
			capsViewer.setInput(capabilities);

			String label;
			if (!sourceLoaders.isEmpty()) {
				StringBuilder builder = new StringBuilder();
				String delim = "";
				boolean shortLabel = sourceLoaders.size() > 1;
				for (CapReqLoader l : sourceLoaders) {
					builder.append(delim);
					builder.append(shortLabel ? l.getShortLabel() : l.getLongLabel());
					delim = ", ";
				}
				label = builder.toString();
			} else {
				label = "<no input>";
			}
			setContentDescription(label);
		}
	}

	@Override
	public void selectionChanged(IWorkbenchPart part, ISelection selection) {
		if (selection == null || !(selection instanceof IStructuredSelection))
			return;

		Set<CapReqLoader> loaders = getLoadersFromSelection((IStructuredSelection) selection);
		if (setLoaders(loaders)) {
			IWorkbenchPage page = getSite().getPage();
			if (page != null && page.isPartVisible(this)) {
				executeAnalysis();
			} else {
				outOfDate = true;
			}
		}
	}

	private Set<CapReqLoader> getLoadersFromSelection(IStructuredSelection structSel) {
		Set<CapReqLoader> result = new LinkedHashSet<>();
		Iterator<?> iter = structSel.iterator();
		while (iter.hasNext()) {

			Object element = iter.next();
			CapReqLoader loader = null;

			File file = SelectionUtils.adaptObject(element, File.class);
			if (file != null) {
				loader = getLoaderForFile(file);
			} else {
				IResource eresource = SelectionUtils.adaptObject(element, IResource.class);
				if (eresource != null) {
					IPath location = eresource.getLocation();
					if (location != null) {
						loader = getLoaderForFile(location.toFile());
					}
				} else if (element instanceof Repository repo) {
					ResourceUtils.getAllResources(repo)
						.stream()
						.filter(r -> {
							try {
								return ResourceUtils.getContentCapabilities(r) != null;
							} catch (Exception e) {
								return false;
							}
						})
						.map(ResourceCapReqLoader::new)
						.forEach(result::add);
				} else if (element instanceof RepositoryResourceElement) {
					Resource resource = ((RepositoryResourceElement) element).getResource();
					loader = new ResourceCapReqLoader(resource);
				}
			}

			if (loader != null)
				result.add(loader);
		}

		return result;
	}

	void executeAnalysis() {
		if (inputLocked)
			return;

		outOfDate = false;
		synchronized (this) {
			Job oldJob = analysisJob;
			if (oldJob != null && oldJob.getState() != Job.NONE)
				oldJob.cancel();

			if (!loaders.isEmpty()) {
				final AnalyseBundleResolutionJob job = new AnalyseBundleResolutionJob("importExportAnalysis", loaders,
					ees.get(currentEE));
				job.setSystem(true);

				job.addJobChangeListener(new JobChangeAdapter() {
					@Override
					public void aboutToRun(IJobChangeEvent event) {
						if (display != null && !display.isDisposed()) {
							Runnable update = () -> setContentDescription("Working...");
							if (display.getThread() == Thread.currentThread())
								update.run();
							else
								display.asyncExec(update);
						}
					}

					@Override
					public void done(IJobChangeEvent event) {
						IStatus result = job.getResult();
						if (result != null && result.isOK()) {
							if (display != null && !display.isDisposed())
								display
									.asyncExec(() -> setInput(loaders, job.getCapabilities(), job.getRequirements()));
						}
					}
				});

				analysisJob = job;
				analysisJob.schedule(500);
			} else {
				analysisJob = null;
			}
		}
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		if (!loaders.isEmpty()) {
			IWorkspaceRoot wsroot = ResourcesPlugin.getWorkspace()
				.getRoot();
			for (CapReqLoader loader : loaders) {
				if (loader instanceof BndBuilderCapReqLoader) {
					File file = ((BndBuilderCapReqLoader) loader).getFile();
					IFile[] wsfiles = wsroot.findFilesForLocationURI(file.toURI());
					for (IFile wsfile : wsfiles) {
						if (event.getDelta()
							.findMember(wsfile.getFullPath()) != null) {
							executeAnalysis();
							break;
						}
					}
				}
			}
		}
	}

	static class LocalTransferDragListener implements DragSourceListener {

		private final Viewer viewer;

		public LocalTransferDragListener(Viewer viewer) {
			this.viewer = viewer;
		}

		@Override
		public void dragStart(DragSourceEvent event) {}

		@Override
		public void dragSetData(DragSourceEvent event) {
			LocalSelectionTransfer transfer = LocalSelectionTransfer.getTransfer();
			if (transfer.isSupportedType(event.dataType))
				transfer.setSelection(viewer.getSelection());
		}

		@Override
		public void dragFinished(DragSourceEvent event) {}
	}

}
