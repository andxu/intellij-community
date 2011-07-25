/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.ide.scopeView;

import com.intellij.ProjectTopics;
import com.intellij.history.LocalHistory;
import com.intellij.history.LocalHistoryAction;
import com.intellij.ide.CopyPasteDelegator;
import com.intellij.ide.DeleteProvider;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.IdeView;
import com.intellij.ide.dnd.aware.DnDAwareTree;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.ide.projectView.impl.AbstractProjectViewPane;
import com.intellij.ide.projectView.impl.ModuleGroup;
import com.intellij.ide.projectView.impl.ProjectViewPane;
import com.intellij.ide.scopeView.nodes.BasePsiNode;
import com.intellij.ide.util.DeleteHandler;
import com.intellij.ide.util.DirectoryChooserUtil;
import com.intellij.ide.util.EditorHelper;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.roots.ui.configuration.actions.ModuleDeleteProvider;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.FileStatusListener;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.packageDependencies.DefaultScopesProvider;
import com.intellij.packageDependencies.DependencyValidationManager;
import com.intellij.packageDependencies.ui.*;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.*;
import com.intellij.psi.search.scope.packageSet.NamedScope;
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder;
import com.intellij.psi.search.scope.packageSet.PackageSet;
import com.intellij.psi.search.scope.packageSet.PackageSetBase;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.ui.*;
import com.intellij.util.EditSourceOnDoubleClickHandler;
import com.intellij.util.Function;
import com.intellij.util.containers.HashSet;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.UiNotifyConnector;
import com.intellij.util.ui.update.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * User: anna
 * Date: 25-Jan-2006
 */
public class ScopeTreeViewPanel extends JPanel implements Disposable {
  private static final Logger LOG = Logger.getInstance("com.intellij.ide.scopeView.ScopeTreeViewPanel");
  private final IdeView myIdeView = new MyIdeView();
  private final MyPsiTreeChangeAdapter myPsiTreeChangeAdapter = new MyPsiTreeChangeAdapter();

  private final DnDAwareTree myTree = new DnDAwareTree();
  private final Project myProject;
  private FileTreeModelBuilder myBuilder;

  private String CURRENT_SCOPE_NAME;

  private TreeExpansionMonitor myTreeExpansionMonitor;
  private CopyPasteDelegator myCopyPasteDelegator;
  private final MyDeletePSIElementProvider myDeletePSIElementProvider = new MyDeletePSIElementProvider();
  private final ModuleDeleteProvider myDeleteModuleProvider = new ModuleDeleteProvider();
  private final DependencyValidationManager myDependencyValidationManager;
  private final WolfTheProblemSolver.ProblemListener myProblemListener = new MyProblemListener();
  private final FileStatusListener myFileStatusListener = new FileStatusListener() {
    @Override
    public void fileStatusesChanged() {
      final List<TreePath> treePaths = TreeUtil.collectExpandedPaths(myTree);
      for (TreePath treePath : treePaths) {
        final Object component = treePath.getLastPathComponent();
        if (component instanceof PackageDependenciesNode) {
          ((PackageDependenciesNode)component).updateColor();
        }
      }
    }

    @Override
    public void fileStatusChanged(@NotNull VirtualFile virtualFile) {
      if (!virtualFile.isValid()) return;
      final PsiFile file = PsiManager.getInstance(myProject).findFile(virtualFile);
      if (file != null) {
        final PackageDependenciesNode node = myBuilder.getFileParentNode(virtualFile);
        final PackageDependenciesNode[] nodes = FileTreeModelBuilder.findNodeForPsiElement(node, file);
        if (nodes != null) {
          for (PackageDependenciesNode dependenciesNode : nodes) {
            dependenciesNode.updateColor();
          }
        }
      }
    }
  };

  private final MergingUpdateQueue myUpdateQueue = new MergingUpdateQueue("ScopeViewUpdate", 300, isTreeShowing(), myTree);
  private ScopeTreeViewPanel.MyChangesListListener myChangesListListener = new MyChangesListListener();

  public ScopeTreeViewPanel(final Project project) {
    super(new BorderLayout());
    myUpdateQueue.setPassThrough(false);  // we don't want passthrough mode, even in unit tests
    myProject = project;
    initTree();

    add(ScrollPaneFactory.createScrollPane(myTree), BorderLayout.CENTER);
    myDependencyValidationManager = DependencyValidationManager.getInstance(myProject);

    final UiNotifyConnector uiNotifyConnector = new UiNotifyConnector(myTree, myUpdateQueue);
    Disposer.register(this, myUpdateQueue);
    Disposer.register(this, uiNotifyConnector);

    if (isTreeShowing()) {
      myUpdateQueue.showNotify();
    }
  }

  public void initListeners() {
    final MessageBusConnection connection = myProject.getMessageBus().connect(this);
    connection.subscribe(ProjectTopics.PROJECT_ROOTS, new MyModuleRootListener());
    PsiManager.getInstance(myProject).addPsiTreeChangeListener(myPsiTreeChangeAdapter);
    WolfTheProblemSolver.getInstance(myProject).addProblemListener(myProblemListener);
    ChangeListManager.getInstance(myProject).addChangeListListener(myChangesListListener);
    FileStatusManager.getInstance(myProject).addFileStatusListener(myFileStatusListener, myProject);
  }

  public void dispose() {
    FileTreeModelBuilder.clearCaches(myProject);
    PsiManager.getInstance(myProject).removePsiTreeChangeListener(myPsiTreeChangeAdapter);
    WolfTheProblemSolver.getInstance(myProject).removeProblemListener(myProblemListener);
    ChangeListManager.getInstance(myProject).removeChangeListListener(myChangesListListener);
  }

  public void selectNode(final PsiElement element, final PsiFile file, final boolean requestFocus) {
    myUpdateQueue.queue(new Update("Select") {
      public void run() {
        if (myProject.isDisposed()) return;
        PackageDependenciesNode node = myBuilder.findNode(file, element);
        if (node != null && node.getPsiElement() != element) {
          final TreePath path = new TreePath(node.getPath());
          if (myTree.isCollapsed(path)) {
            myTree.expandPath(path);
            myTree.makeVisible(path);
          }
        }
        node = myBuilder.findNode(file, element);
        if (node != null) {
          TreeUtil.selectPath(myTree, new TreePath(node.getPath()));
          if (requestFocus) {
            myTree.requestFocus();
          }
        }
      }
    });
  }

  public void selectScope(final NamedScope scope) {
    refreshScope(scope, true);
    if (scope != DefaultScopesProvider.getAllScope()) {
      CURRENT_SCOPE_NAME = scope.getName();
    }
  }

  public JPanel getPanel() {
    return this;
  }

  private void initTree() {
    myTree.setCellRenderer(new MyTreeCellRenderer());
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(true);
    UIUtil.setLineStyleAngled(myTree);
    TreeUtil.installActions(myTree);
    EditSourceOnDoubleClickHandler.install(myTree);
    new TreeSpeedSearch(myTree);
    myCopyPasteDelegator = new CopyPasteDelegator(myProject, this) {
      @NotNull
      protected PsiElement[] getSelectedElements() {
        return getSelectedPsiElements();
      }
    };
    myTreeExpansionMonitor = PackageTreeExpansionMonitor.install(myTree, myProject);
    for (ScopeTreeStructureExpander expander : Extensions.getExtensions(ScopeTreeStructureExpander.EP_NAME, myProject)) {
      myTree.addTreeWillExpandListener(expander);
    }
  }

  @NotNull
  private PsiElement[] getSelectedPsiElements() {
    final TreePath[] treePaths = myTree.getSelectionPaths();
    if (treePaths != null) {
      Set<PsiElement> result = new HashSet<PsiElement>();
      for (TreePath path : treePaths) {
        PackageDependenciesNode node = (PackageDependenciesNode)path.getLastPathComponent();
        final PsiElement psiElement = node.getPsiElement();
        if (psiElement != null && psiElement.isValid()) {
          result.add(psiElement);
        }
      }
      return PsiUtilBase.toPsiElementArray(result);
    }
    return PsiElement.EMPTY_ARRAY;
  }

  private void refreshScope(@Nullable NamedScope scope, boolean showProgress) {
    FileTreeModelBuilder.clearCaches(myProject);
    myTreeExpansionMonitor.freeze();
    if (scope == null || scope.getValue() == null) { //was deleted
      scope = DefaultScopesProvider.getAllScope();
    }
    LOG.assertTrue(scope != null);
    final NamedScopesHolder holder = NamedScopesHolder.getHolder(myProject, scope.getName(), myDependencyValidationManager);
    final PackageSet packageSet = scope.getValue();
    final DependenciesPanel.DependencyPanelSettings settings = new DependenciesPanel.DependencyPanelSettings();
    settings.UI_FILTER_LEGALS = true;
    settings.UI_GROUP_BY_SCOPE_TYPE = false;
    settings.UI_SHOW_FILES = true;
    final ProjectView projectView = ProjectView.getInstance(myProject);
    settings.UI_FLATTEN_PACKAGES = projectView.isFlattenPackages(ScopeViewPane.ID);
    settings.UI_COMPACT_EMPTY_MIDDLE_PACKAGES = projectView.isHideEmptyMiddlePackages(ScopeViewPane.ID);
    myBuilder = new FileTreeModelBuilder(myProject, new Marker() {
      public boolean isMarked(VirtualFile file) {
        return packageSet != null && (packageSet instanceof PackageSetBase ? ((PackageSetBase)packageSet).contains(file, holder) : packageSet.contains(PackageSetBase.getPsiFile(file, holder), holder));
      }
    }, settings);
    myTree.setPaintBusy(true);
    myBuilder.setTree(myTree);
    myTree.getEmptyText().setText("Loading...");
    myTree.setModel(myBuilder.build(myProject, showProgress, new Runnable(){
      @Override
      public void run() {
        myTree.setPaintBusy(false);
        myTree.getEmptyText().setText(UIBundle.message("message.nothingToShow"));
      }
    }));
    ((PackageDependenciesNode)myTree.getModel().getRoot()).sortChildren();
    ((DefaultTreeModel)myTree.getModel()).reload();
    myTreeExpansionMonitor.restore();
    FileTreeModelBuilder.clearCaches(myProject);
  }

  private NamedScope getCurrentScope() {
    NamedScope scope = NamedScopesHolder.getScope(myProject, CURRENT_SCOPE_NAME);
    if (scope == null) {
      scope = DefaultScopesProvider.getAllScope();
    }
    LOG.assertTrue(scope != null);
    return scope;
  }

  @Nullable
  public Object getData(String dataId) {
    if (LangDataKeys.MODULE_CONTEXT.is(dataId)) {
      final TreePath selectionPath = myTree.getSelectionPath();
      if (selectionPath != null) {
        PackageDependenciesNode node = (PackageDependenciesNode)selectionPath.getLastPathComponent();
        if (node instanceof ModuleNode) {
          return ((ModuleNode)node).getModule();
        }
      }
    }
    if (LangDataKeys.PSI_ELEMENT.is(dataId)) {
      final TreePath selectionPath = myTree.getSelectionPath();
      if (selectionPath != null) {
        PackageDependenciesNode node = (PackageDependenciesNode)selectionPath.getLastPathComponent();
        return node != null && node.isValid() ? node.getPsiElement() : null;
      }
    }
    final TreePath[] treePaths = myTree.getSelectionPaths();
    if (treePaths != null) {
      if (LangDataKeys.PSI_ELEMENT_ARRAY.is(dataId)) {
        Set<PsiElement> psiElements = new HashSet<PsiElement>();
        for (TreePath treePath : treePaths) {
          final PackageDependenciesNode node = (PackageDependenciesNode)treePath.getLastPathComponent();
          if (node.isValid()) {
            final PsiElement psiElement = node.getPsiElement();
            if (psiElement != null) {
              psiElements.add(psiElement);
            }
          }
        }
        return psiElements.isEmpty() ? null : PsiUtilBase.toPsiElementArray(psiElements);
      }
    }
    if (LangDataKeys.IDE_VIEW.is(dataId)) {
      return myIdeView;
    }
    if (PlatformDataKeys.CUT_PROVIDER.is(dataId)) {
      return myCopyPasteDelegator.getCutProvider();
    }
    if (PlatformDataKeys.COPY_PROVIDER.is(dataId)) {
      return myCopyPasteDelegator.getCopyProvider();
    }
    if (PlatformDataKeys.PASTE_PROVIDER.is(dataId)) {
      return myCopyPasteDelegator.getPasteProvider();
    }
    if (PlatformDataKeys.DELETE_ELEMENT_PROVIDER.is(dataId)) {
      if (getSelectedModules() != null) {
        return myDeleteModuleProvider;
      }
      return myDeletePSIElementProvider;
    }
    if (LangDataKeys.PASTE_TARGET_PSI_ELEMENT.is(dataId)) {
      final TreePath selectionPath = myTree.getSelectionPath();
      if (selectionPath != null) {
        final Object pathComponent = selectionPath.getLastPathComponent();
        if (pathComponent instanceof DirectoryNode) {
          return ((DirectoryNode)pathComponent).getTargetDirectory();
        }
      }
    }
    return null;
  }

  @Nullable
  private Module[] getSelectedModules() {
    final TreePath[] treePaths = myTree.getSelectionPaths();
    if (treePaths != null) {
      Set<Module> result = new HashSet<Module>();
      for (TreePath path : treePaths) {
        PackageDependenciesNode node = (PackageDependenciesNode)path.getLastPathComponent();
        if (node instanceof ModuleNode) {
          result.add(((ModuleNode)node).getModule());
        }
        else if (node instanceof ModuleGroupNode) {
          final ModuleGroupNode groupNode = (ModuleGroupNode)node;
          final ModuleGroup moduleGroup = groupNode.getModuleGroup();
          result.addAll(moduleGroup.modulesInGroup(myProject, true));
        }
      }
      return result.isEmpty() ? null : result.toArray(new Module[result.size()]);
    }
    return null;
  }

  private void reload(@Nullable final DefaultMutableTreeNode rootToReload) {
    final DefaultTreeModel treeModel = (DefaultTreeModel)myTree.getModel();
    if (rootToReload != null) {
      final List<TreePath> treePaths = TreeUtil.collectExpandedPaths(myTree, new TreePath(rootToReload.getPath()));
      ((DefaultTreeModel)myTree.getModel()).reload(rootToReload);
      TreePath path = new TreePath(rootToReload.getPath());
      if (!myTree.isCollapsed(path)) {
        myTree.collapsePath(path);
        for (TreePath treePath : treePaths) {
          myTree.expandPath(treePath);
        }
      }
      TreeUtil.sort(rootToReload, getNodeComparator());
    }
    else {
      TreeUtil.sort(treeModel, getNodeComparator());
      treeModel.reload();
    }
  }

  private DependencyNodeComparator getNodeComparator() {
    return new DependencyNodeComparator(ProjectView.getInstance(myProject).isSortByType(ScopeViewPane.ID));
  }

  public void setSortByType() {
    myTreeExpansionMonitor.freeze();
    reload(null);
    myTreeExpansionMonitor.restore();
  }

  private class MyTreeCellRenderer extends ColoredTreeCellRenderer {
    public void customizeCellRenderer(JTree tree,
                                      Object value,
                                      boolean selected,
                                      boolean expanded,
                                      boolean leaf,
                                      int row,
                                      boolean hasFocus) {
      if (value instanceof PackageDependenciesNode) {
        PackageDependenciesNode node = (PackageDependenciesNode)value;
        try {
          if (expanded) {
            setIcon(node.getOpenIcon());
          }
          else {
            setIcon(node.getClosedIcon());
          }
        }
        catch (IndexNotReadyException ignore) {
        }
        final SimpleTextAttributes regularAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES;
        TextAttributes textAttributes = regularAttributes.toTextAttributes();
        if (node instanceof BasePsiNode && ((BasePsiNode)node).isDeprecated()) {
          textAttributes =
              EditorColorsManager.getInstance().getGlobalScheme().getAttributes(CodeInsightColors.DEPRECATED_ATTRIBUTES).clone();
        }
        final PsiElement psiElement = node.getPsiElement();
        textAttributes.setForegroundColor(CopyPasteManager.getInstance().isCutElement(psiElement) ? CopyPasteManager.CUT_COLOR : node.getColor());
        append(node.toString(), SimpleTextAttributes.fromTextAttributes(textAttributes));

        String oldToString = toString();
        for(ProjectViewNodeDecorator decorator: Extensions.getExtensions(ProjectViewNodeDecorator.EP_NAME, myProject)) {
          decorator.decorate(node, this);          
        }
        if (toString().equals(oldToString)) {   // nothing was decorated
          final String locationString = node.getComment();
          if (locationString != null && locationString.length() > 0) {
            append(" (" + locationString + ")", SimpleTextAttributes.GRAY_ATTRIBUTES);
          }
        }
      }
    }
  }

  private class MyPsiTreeChangeAdapter extends PsiTreeChangeAdapter {
    public void childAdded(final PsiTreeChangeEvent event) {
      final PsiElement element = event.getParent();
      final PsiElement child = event.getChild();
      if (child == null) return;
      if (element.getContainingFile() == null) {
        queueUpdate(new Runnable() {
          public void run() {
            if (!child.isValid()) return;
            processNodeCreation(child);
          }
        }, false);
      }
    }

    private void processNodeCreation(final PsiElement psiElement) {
      if (psiElement instanceof PsiFile && !isInjected((PsiFile)psiElement)) {
        reload(myBuilder.addFileNode((PsiFile)psiElement));
      }
      else if (psiElement instanceof PsiDirectory) {
        final PsiElement[] children = psiElement.getChildren();
        for (PsiElement child : children) {
          processNodeCreation(child);
        }
      }
    }

    public void beforeChildRemoval(final PsiTreeChangeEvent event) {
      final PsiElement child = event.getChild();
      final PsiElement parent = event.getParent();
      if (parent instanceof PsiDirectory && (child instanceof PsiFile && !isInjected((PsiFile)child) || child instanceof PsiDirectory)) {
        queueUpdate(new Runnable() {
          public void run() {
            reload(myBuilder.removeNode(child, (PsiDirectory)parent));
          }
        }, true);
      }
    }

    public void childMoved(PsiTreeChangeEvent event) {
      final PsiElement oldParent = event.getOldParent();
      final PsiElement newParent = event.getNewParent();
      final PsiElement child = event.getChild();
      if (oldParent instanceof PsiDirectory && newParent instanceof PsiDirectory) {
        if (child instanceof PsiFile && !isInjected((PsiFile)child)) {
          final PsiFile file = (PsiFile)child;
          queueUpdate(new Runnable() {
            public void run() {
              reload(myBuilder.removeNode(child, (PsiDirectory)oldParent));
              final VirtualFile virtualFile = file.getVirtualFile();
              if (virtualFile != null) {
                final PsiFile newFile = file.isValid() ? file : PsiManager.getInstance(myProject).findFile(virtualFile);
                if (newFile != null) {
                  reload(myBuilder.addFileNode(newFile));
                }
              }
            }
          }, true);
        }
      }
    }


    public void childrenChanged(PsiTreeChangeEvent event) {
      final PsiElement parent = event.getParent();
      final PsiFile file = parent.getContainingFile();
      if (file != null && file.getFileType() == StdFileTypes.JAVA) {
        if (!file.getViewProvider().isPhysical() && !isInjected(file)) return;
        queueUpdate(new Runnable() {
          public void run() {
            if (file.isValid()) {
              reload(myBuilder.getFileParentNode(file.getVirtualFile()));
            }
          }
        }, false);
      }
    }

    public final void propertyChanged(PsiTreeChangeEvent event) {
      String propertyName = event.getPropertyName();
      final PsiElement element = event.getElement();
      if (element != null) {
        final NamedScope scope = getCurrentScope();
        if (propertyName.equals(PsiTreeChangeEvent.PROP_FILE_NAME) || propertyName.equals(PsiTreeChangeEvent.PROP_FILE_TYPES)) {
          queueUpdate(new Runnable() {
            public void run() {
              if (element.isValid()) {
                processRenamed(scope, element.getContainingFile());
              }
            }
          }, false);
        }
        else if (propertyName.equals(PsiTreeChangeEvent.PROP_DIRECTORY_NAME)) {
          queueRefreshScope(scope);
        }
      }
    }

    public void childReplaced(final PsiTreeChangeEvent event) {
      final NamedScope scope = getCurrentScope();
      final PsiElement element = event.getNewChild();
      final PsiFile psiFile = event.getFile();
      if (psiFile != null && !isInjected(psiFile)) {
        if (psiFile.getLanguage() == psiFile.getViewProvider().getBaseLanguage()) {
          queueUpdate(new Runnable() {
            public void run() {
              processRenamed(scope, psiFile);
            }
          }, false);
        }
      }
      else if (element instanceof PsiDirectory && element.isValid()) {
        queueRefreshScope(scope);
      }
    }

    private boolean isInjected(PsiFile psiFile) {
      return InjectedLanguageManager.getInstance(myProject).isInjectedFragment(psiFile);
    }

    private void queueRefreshScope(final NamedScope scope) {
      myUpdateQueue.cancelAllUpdates();
      queueUpdate(new Runnable() {
        public void run() {
          refreshScope(scope, true);
        }
      }, false);
    }

    private void processRenamed(final NamedScope scope, final PsiFile file) {
      if (!file.isValid() || !file.getViewProvider().isPhysical()) return;
      final PackageSet packageSet = scope.getValue();
      if (packageSet == null) return; //invalid scope selected
      if (packageSet.contains(file, NamedScopesHolder.getHolder(myProject, scope.getName(), myDependencyValidationManager))) {
        reload(myBuilder.findNode(file, file));
      }
      else {
        reload(myBuilder.removeNode(file, file.getParent()));
      }
    }

    //expand/collapse state should be restored in actual request if needed
    private void queueUpdate(final Runnable request, boolean updateImmediately) {
      final Runnable wrapped = new Runnable() {
        public void run() {
          if (myProject.isDisposed()) return;
          request.run();
        }
      };
      if (updateImmediately && isTreeShowing()) {
        myUpdateQueue.run(new Update(request) {
          public void run() {
            wrapped.run();
          }
        });
      }
      else {
        myUpdateQueue.queue(new Update(request) {
          public void run() {
            wrapped.run();
          }

          public boolean isExpired() {
            return !isTreeShowing();
          }
        });
      }
    }
  }

  private class MyModuleRootListener implements ModuleRootListener {
    public void beforeRootsChange(ModuleRootEvent event) {
    }

    public void rootsChanged(ModuleRootEvent event) {
      myUpdateQueue.cancelAllUpdates();
      myUpdateQueue.queue(new Update("RootsChanged") {
        public void run() {
          refreshScope(getCurrentScope(), false);
        }

        public boolean isExpired() {
          return !isTreeShowing();
        }
      });
    }
  }

  private class MyIdeView implements IdeView {
    public void selectElement(final PsiElement element) {
      if (element != null) {
        final boolean isDirectory = element instanceof PsiDirectory;
        if (!isDirectory) {
          final PsiFile psiFile = element.getContainingFile();
          final PackageSet packageSet = getCurrentScope().getValue();
          if (packageSet == null) return;
          if (psiFile != null) {
            final ProjectView projectView = ProjectView.getInstance(myProject);
            if (!packageSet.contains(psiFile, NamedScopesHolder.getHolder(myProject, CURRENT_SCOPE_NAME, myDependencyValidationManager))) {
              projectView.changeView(ProjectViewPane.ID);
            }
            projectView.select(psiFile, psiFile.getVirtualFile(), false);
          }
          Editor editor = EditorHelper.openInEditor(element);
          if (editor != null) {
            ToolWindowManager.getInstance(myProject).activateEditorComponent();
          }
        }
        else {
          ((PsiDirectory)element).navigate(true);
        }
      }
    }

    @Nullable
    private PsiDirectory getDirectory() {
      final TreePath[] selectedPaths = myTree.getSelectionPaths();
      if (selectedPaths != null) {
        if (selectedPaths.length != 1) return null;
        TreePath path = selectedPaths[0];
        final PackageDependenciesNode node = (PackageDependenciesNode)path.getLastPathComponent();
        if (!node.isValid()) return null;
        if (node instanceof DirectoryNode) {
          DirectoryNode directoryNode = (DirectoryNode)node;
          while (directoryNode.getCompactedDirNode() != null) {
            directoryNode = directoryNode.getCompactedDirNode();
            LOG.assertTrue(directoryNode != null);
          }
          return (PsiDirectory)directoryNode.getPsiElement();
        }
        else if (node instanceof BasePsiNode) {
          final PsiElement psiElement = node.getPsiElement();
          LOG.assertTrue(psiElement != null);
          final PsiFile psiFile = psiElement.getContainingFile();
          LOG.assertTrue(psiFile != null);
          return psiFile.getContainingDirectory();
        }
        else if (node instanceof FileNode) {
          final PsiFile psiFile = (PsiFile)node.getPsiElement();
          LOG.assertTrue(psiFile != null);
          return psiFile.getContainingDirectory();
        }
      }
      return null;
    }

    public PsiDirectory[] getDirectories() {
      PsiDirectory directory = getDirectory();
      return directory == null ? PsiDirectory.EMPTY_ARRAY : new PsiDirectory[]{directory};
    }

    @Nullable
    public PsiDirectory getOrChooseDirectory() {
      return DirectoryChooserUtil.getOrChooseDirectory(this);
    }
  }

  private final class MyDeletePSIElementProvider implements DeleteProvider {
    public boolean canDeleteElement(DataContext dataContext) {
      final PsiElement[] elements = getSelectedPsiElements();
      return DeleteHandler.shouldEnableDeleteAction(elements);
    }

    public void deleteElement(DataContext dataContext) {
      List<PsiElement> allElements = Arrays.asList(getSelectedPsiElements());
      ArrayList<PsiElement> validElements = new ArrayList<PsiElement>();
      for (PsiElement psiElement : allElements) {
        if (psiElement != null && psiElement.isValid()) validElements.add(psiElement);
      }
      final PsiElement[] elements = PsiUtilBase.toPsiElementArray(validElements);

      LocalHistoryAction a = LocalHistory.getInstance().startAction(IdeBundle.message("progress.deleting"));
      try {
        DeleteHandler.deletePsiElement(elements, myProject);
      }
      finally {
        a.finish();
      }
    }
  }

  public DnDAwareTree getTree() {
    return myTree;
  }

  private class MyProblemListener extends WolfTheProblemSolver.ProblemListener {
    public void problemsAppeared(VirtualFile file) {
      addNode(file, DefaultScopesProvider.getInstance(myProject).getProblemsScope().getName());
    }

    public void problemsDisappeared(VirtualFile file) {
      removeNode(file, DefaultScopesProvider.getInstance(myProject).getProblemsScope().getName());
    }
  }

  private void addNode(VirtualFile file, final String scopeName) {
    queueUpdate(file, new Function<PsiFile, DefaultMutableTreeNode>() {
      @Nullable
      public DefaultMutableTreeNode fun(final PsiFile psiFile) {
        return myBuilder.addFileNode(psiFile);
      }
    }, scopeName);
  }

  private void removeNode(VirtualFile file, final String scopeName) {
    queueUpdate(file, new Function<PsiFile, DefaultMutableTreeNode>() {
      @Nullable
      public DefaultMutableTreeNode fun(final PsiFile psiFile) {
        return myBuilder.removeNode(psiFile, psiFile.getContainingDirectory());
      }
    }, scopeName);
  }

  private void queueUpdate(final VirtualFile fileToRefresh,
                           final Function<PsiFile, DefaultMutableTreeNode> rootToReloadGetter, final String scopeName) {
    AbstractProjectViewPane pane = ProjectView.getInstance(myProject).getCurrentProjectViewPane();
    if (pane == null || !ScopeViewPane.ID.equals(pane.getId()) ||
        !scopeName.equals(pane.getSubId())) {
      return;
    }
    myUpdateQueue.queue(new Update(fileToRefresh) {
      public void run() {
        if (myProject.isDisposed() || !fileToRefresh.isValid()) return;
        final PsiFile psiFile = PsiManager.getInstance(myProject).findFile(fileToRefresh);
        if (psiFile != null) {
          reload(rootToReloadGetter.fun(psiFile));
        }
      }

      public boolean isExpired() {
        return !isTreeShowing();
      }
    });
  }

  private boolean isTreeShowing() {
    return myTree.isShowing() || ApplicationManager.getApplication().isUnitTestMode();
  }

  private class MyChangesListListener extends ChangeListAdapter {
    @Override
    public void changeListAdded(ChangeList list) {
      fireListeners(list, null);
    }

    @Override
    public void changeListRemoved(ChangeList list) {
      fireListeners(list, null);
    }

    @Override
    public void changeListRenamed(ChangeList list, String oldName) {
      fireListeners(list, oldName);
    }

    private void fireListeners(ChangeList list, @Nullable String oldName) {
      AbstractProjectViewPane pane = ProjectView.getInstance(myProject).getCurrentProjectViewPane();
      if (pane == null || !ScopeViewPane.ID.equals(pane.getId())) {
        return;
      }
      final String subId = pane.getSubId();
      if (!list.getName().equals(subId) && (oldName == null || !oldName.equals(subId))) {
        return;
      }
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        public void run() {
          myDependencyValidationManager.fireScopeListeners();
        }
      }, myProject.getDisposed());
    }

    @Override
    public void changesRemoved(Collection<Change> changes, ChangeList fromList) {
      final String name = fromList.getName();
      final Set<VirtualFile> files = new HashSet<VirtualFile>();
      collectFiles(changes, files);
      for (VirtualFile file : files) {
        removeNode(file, name);
      }
    }

    @Override
    public void changesAdded(Collection<Change> changes, ChangeList toList) {
      final String name = toList.getName();
      final Set<VirtualFile> files = new HashSet<VirtualFile>();
      collectFiles(changes, files);
      for (VirtualFile file : files) {
        addNode(file, name);
      }
    }

    private void collectFiles(Collection<Change> changes, Set<VirtualFile> files) {
      for (Change change : changes) {
        final ContentRevision afterRevision = change.getAfterRevision();
        if (afterRevision != null) {
          final VirtualFile virtualFile = afterRevision.getFile().getVirtualFile();
          if (virtualFile != null) {
            files.add(virtualFile);
          }
        }
      }
    }
  }
}
