// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.npm;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.Authenticator.RequestorType;
import java.net.PasswordAuthentication;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.EtchedBorder;

import org.netbeans.spi.keyring.KeyringProvider;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.WindowGeometry;
import org.openstreetmap.josm.gui.widgets.HtmlPanel;
import org.openstreetmap.josm.io.DefaultProxySelector;
import org.openstreetmap.josm.io.auth.CredentialsAgentException;
import org.openstreetmap.josm.io.auth.CredentialsManager;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.PlatformManager;

public class InitializationWizard extends JDialog {

    protected boolean canceled;
    protected JButton btnCancel, btnBack, btnNext;
    protected Action nextAction, finishAction;
    protected JPanel cardPanel;
    
    List<WizardPanel> panels = new ArrayList<>();
    int panelIndex;
    
    private CardLayout cardLayout;
    
    public InitializationWizard() {
        super(JOptionPane.getFrameForComponent(MainApplication.getMainFrame()),
                tr("Native password manager plugin"), ModalityType.DOCUMENT_MODAL);
        build();
        NPMType npm = detectNativePasswordManager();
        WizardPanel firstPanel;
        if (npm == null) {
            firstPanel = new NothingFoundPanel();
        } else {
            firstPanel = new SelectionPanel(npm, this);
        }
        panelIndex = 0;
        panels.add(firstPanel);
        cardPanel.add(firstPanel.getPanel(), firstPanel.getId());
        
        updateButtons();
    }
    
    private void build() {
        Container c = getContentPane();
        c.setLayout(new BorderLayout());
        addWindowListener(new WindowEventHandler());

        getRootPane().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new CancelAction());

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.setBorder(new EmptyBorder(new Insets(5, 10, 5, 10)));
        c.add(cardPanel, BorderLayout.CENTER);
        
        nextAction = new NextAction();
        finishAction = new FinishAction();
        btnCancel = new JButton(new CancelAction());
        btnBack = new JButton(new BackAction());
        btnNext = new JButton(nextAction);
        
        Box buttonsBox = new Box(BoxLayout.X_AXIS);
        buttonsBox.setBorder(new EmptyBorder(new Insets(5, 10, 5, 10)));
        buttonsBox.add(btnCancel);
        buttonsBox.add(Box.createHorizontalStrut(30));
        buttonsBox.add(btnBack);
        buttonsBox.add(Box.createHorizontalStrut(10));
        buttonsBox.add(btnNext);
        
        JPanel buttonsPanel = new JPanel(new BorderLayout());
        buttonsPanel.add(new JSeparator(), BorderLayout.NORTH);
        buttonsPanel.add(buttonsBox, BorderLayout.EAST);
        c.add(buttonsPanel, BorderLayout.SOUTH);
    }
        
    private void updateButtons() {
        btnBack.setEnabled(panelIndex > 0);
        if (panels.get(panelIndex).isLast()) {
            btnNext.setAction(finishAction);
        } else {
            btnNext.setAction(nextAction);
        }
    }
    
    /**
     * A WizardPanel represents one page in the wizard dialog.
     * The user usually proceeds from one panel to the next, but can go back as well.
     */
    public interface WizardPanel {
        /* unique id */
        String getId();
        
        /* get the Panel Compoment */
        JPanel getPanel();
        
        /* return true if this page is the last and the 'next' button should change into 'finish' */
        boolean isLast();
        
        /* Provide the next WizardPanel. 
         * Not called when isLast() returns true. */
        WizardPanel provideNext();
        
        /* The action to execute, when the user finall hits 'OK' and not 'Cancel' */
        void onOkAction();
    }

    private abstract static class AbstractWizardPanel implements WizardPanel {
        
        /**
         * Put a logo to the left, as is customary for wizard dialogs
         */
        @Override
        public JPanel getPanel() {
            JPanel p = new JPanel(new BorderLayout());
            JLabel image = new JLabel(ImageProvider.get("lock-large"));
            image.setBorder(
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createEtchedBorder(EtchedBorder.RAISED),
                        BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            image.setVerticalAlignment(SwingConstants.TOP);
            p.add(image, BorderLayout.WEST);
            
            JPanel content = getContentPanel();
            content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            p.add(content, BorderLayout.CENTER);
            
            return p;
        }
        
        @Override
        public String getId() {
            return getClass().getCanonicalName();
        }

        protected abstract JPanel getContentPanel();
    }
    
    private static class NothingFoundPanel extends AbstractWizardPanel {

        JCheckBox cbDoNotShowAgain;
        
        @Override
        public boolean isLast() {
            return true;
        }
        
        @Override
        public WizardPanel provideNext() {
            return null;
        }

        @Override
        public void onOkAction() {
            if (cbDoNotShowAgain.isSelected()) {
                NPMPlugin.turnOffPermanently();
            }
        }

        @Override
        protected JPanel getContentPanel() {
            JPanel p = new JPanel();
            GroupLayout layout = new GroupLayout(p);
            p.setLayout(layout);

            HtmlPanel intro = new HtmlPanel("<html>"+
                    tr("No native password manager could be found!")+"<br>"+
                    tr("Depending on your Operating System / Distribution, you may have to create a default keyring / wallet first.")+
                    "</html>");

            cbDoNotShowAgain = new JCheckBox("Do not show this wizard again on next start");
            layout.setHorizontalGroup(
                layout.createParallelGroup()
                    .addComponent(intro)
                    .addComponent(cbDoNotShowAgain)
            );

            layout.setVerticalGroup(
                layout.createSequentialGroup()
                    .addComponent(intro, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                    .addComponent(cbDoNotShowAgain)
            );
            
            return p;
        }
    }
    
    private static class SelectionPanel extends AbstractWizardPanel implements ActionListener {
        
        private final NPMType type;
        private final InitializationWizard wizard;
        
        private JRadioButton rbManage, rbPlain;
                
        public SelectionPanel(NPMType type, InitializationWizard wizard) {
            this.type = type;
            this.wizard = wizard;
        }
        
        @Override
        public boolean isLast() {
            return (rbPlain != null && rbPlain.isSelected()) || !hasUnprotectedCedentials();
        }

        @Override
        public WizardPanel provideNext() {
            return new DeleteOldCredentialsPanel();
        }
        
        @Override
        protected JPanel getContentPanel() {
            JPanel p = new JPanel();
            GroupLayout layout = new GroupLayout(p);
            p.setLayout(layout);

            HtmlPanel intro = new HtmlPanel("<html><b>"+type.getIntroText()+"</b></html>");
            rbManage = new JRadioButton("<html>"+type.getSelectionText()+"</html>");
            rbPlain = new JRadioButton("<html>"+tr("No thanks, use JOSM''s plain text preferences storage")+"</html>");
            rbManage.addActionListener(this);
            rbPlain.addActionListener(this);
            
            rbManage.setSelected(true);
            ButtonGroup group = new ButtonGroup();
            group.add(rbManage);
            group.add(rbPlain);

            layout.setHorizontalGroup(
                layout.createParallelGroup()
                    .addComponent(intro)
                    .addComponent(rbManage)
                    .addComponent(rbPlain)
            );

            layout.setVerticalGroup(
                layout.createSequentialGroup()
                    .addComponent(intro, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                    .addComponent(rbManage)
                    .addComponent(rbPlain)
            );
            
            return p;
        }
        
        @Override
        public void onOkAction() {
            if (rbManage.isSelected()) {
                NPMPlugin.selectAndSave(type);
            } else {
                wizard.setCanceled(true);
                NPMPlugin.turnOffPermanently();
            }
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            wizard.updateButtons();
        }
    }

    private static class DeleteOldCredentialsPanel extends AbstractWizardPanel {
        
        private JRadioButton rbClear, rbKeep;
        
        @Override
        public boolean isLast() {
            return true;
        }

        @Override
        public WizardPanel provideNext() {
            return null;
        }

        @Override
        public JPanel getContentPanel() {
            JPanel p = new JPanel();
            GroupLayout layout = new GroupLayout(p);
            p.setLayout(layout);

            HtmlPanel l = new HtmlPanel();
            l.setText("<html><b>"+tr("Found sensitive data that is still saved"
                    + " in JOSM''s preference file (plain text).")+"<b></html>");
            rbClear = new JRadioButton("<html>"+tr("Transfer to password manager and remove from preference file")+"</html>");
            rbKeep = new JRadioButton("<html>"+tr("No, just keep it")+"</html>");
            rbClear.setSelected(true);
            ButtonGroup group = new ButtonGroup();
            group.add(rbClear);
            group.add(rbKeep);

            layout.setHorizontalGroup(
                layout.createParallelGroup()
                    .addComponent(l)
                    .addComponent(rbClear)
                    .addComponent(rbKeep)
            );

            layout.setVerticalGroup(
                layout.createSequentialGroup()
                    .addComponent(l, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                    .addComponent(rbClear)
                    .addComponent(rbKeep)
            );
            
            return p;
        }

        @Override
        public void onOkAction() {
            
            CredentialsManager cm = CredentialsManager.getInstance();

            String proxyUsername = Config.getPref().get(DefaultProxySelector.PROXY_USER, null);
            String proxyPassword = Config.getPref().get(DefaultProxySelector.PROXY_PASS, null);
            String proxyHost = Config.getPref().get(DefaultProxySelector.PROXY_HTTP_HOST, null);
            if (proxyUsername != null || proxyPassword != null) {
                try {
                    cm.store(RequestorType.PROXY, proxyHost, new PasswordAuthentication(string(proxyUsername), toCharArray(proxyPassword)));
                    if (rbClear.isSelected()) {
                        Config.getPref().put(DefaultProxySelector.PROXY_USER, null);
                        Config.getPref().put(DefaultProxySelector.PROXY_PASS, null);
                    }
                } catch (CredentialsAgentException ex) {
                    Logging.error(ex);
                }
            }
        }
    }

    private static final String NPM = "Native Password Manager Plugin: ";

    private static NPMType detectNativePasswordManager() {
        NPMType[] potentialManagers;
        if (PlatformManager.isPlatformWindows()) {
            potentialManagers = new NPMType[] { NPMType.CRYPT32 };
        } else if (PlatformManager.isPlatformOsx()) {
            potentialManagers = new NPMType[] { NPMType.KEYCHAIN };
        } else if (PlatformManager.isPlatformUnixoid()) {
            potentialManagers = new NPMType[] { NPMType.GNOME_KEYRING, NPMType.KWALLET };
        } else
            throw new AssertionError();

        for (NPMType manager : potentialManagers) {
            Logging.info(NPM + "Looking for " + manager.getName());
            KeyringProvider provider = manager.getProvider();
            if (provider.enabled()) {
                Logging.info(NPM + "Found " + manager.getName());
                return manager;
            }
        }
        return null;
    }
    
    private static boolean hasUnprotectedCedentials() {
        return 
            Config.getPref().get("osm-server.username", null) != null ||
            Config.getPref().get("osm-server.password", null) != null ||
            Config.getPref().get(DefaultProxySelector.PROXY_USER, null) != null ||
            Config.getPref().get(DefaultProxySelector.PROXY_PASS, null) != null ||
            Config.getPref().get("oauth.access-token.key", null) != null ||
            Config.getPref().get("oauth.access-token.secret", null) != null;
    }
    
    public void showDialog() {
        pack();
        setVisible(true);
    }
    
    @Override
    public void setVisible(boolean visible) {
        if (visible) {
            new WindowGeometry(
                    getClass().getName() + ".geometry",
                    WindowGeometry.centerInWindow(
                            getParent(),
                            new Dimension(600,400)
                    )
            ).applySafe(this);
        } else if (isShowing()){
            new WindowGeometry(this).remember(getClass().getName() + ".geometry");
        }
        super.setVisible(visible);
    }
    
    public boolean isCanceled() {
        return canceled;
    }

    protected void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    /**
     * Close the dialog and discard all changes.
     */
    class CancelAction extends AbstractAction {
        public CancelAction() {
            putValue(NAME, tr("Cancel"));
            putValue(SMALL_ICON, ImageProvider.get("cancel"));
            putValue(SHORT_DESCRIPTION, tr("Close the dialog and discard all changes"));
        }

        public void cancel() {
            setCanceled(true);
            setVisible(false);
        }

        @Override
        public void actionPerformed(ActionEvent evt) {
            cancel();
        }
    }

    /**
     * Go to the previous page.
     */
    class BackAction extends AbstractAction {
        public BackAction() {
            putValue(NAME, tr("Back"));
            putValue(SMALL_ICON, ImageProvider.get("dialogs", "previous"));
            putValue(SHORT_DESCRIPTION, tr("Go to the previous page"));
        }

        @Override
        public void actionPerformed(ActionEvent evt) {
            if (panelIndex <= 0)
                // Java 9 will let us pass the panelIndex by itself
                throw new IndexOutOfBoundsException("Index out of range: " + panelIndex);
            panelIndex--;
            cardLayout.show(cardPanel, panels.get(panelIndex).getId());
            updateButtons();
        }
    }
    
    class NextAction extends AbstractAction {
        public NextAction() {
            putValue(NAME, tr("Next"));
            putValue(SMALL_ICON, ImageProvider.get("dialogs", "next"));
            putValue(SHORT_DESCRIPTION, tr("Proceed and go to the next page"));
        }

        @Override
        public void actionPerformed(ActionEvent evt) {
            if (panelIndex == panels.size() - 1) {
                WizardPanel next = panels.get(panelIndex).provideNext();
                cardPanel.add(next.getPanel(), next.getId());
                panels.add(next);
            }
            panelIndex++;
            cardLayout.show(cardPanel, panels.get(panelIndex).getId());
            updateButtons();
        }
    }

    class FinishAction extends AbstractAction {
        public FinishAction() {
            putValue(NAME, tr("Finish"));
            putValue(SMALL_ICON, ImageProvider.get("ok"));
            putValue(SHORT_DESCRIPTION, tr("Confirm the setup and close this dialog"));
        }

        @Override
        public void actionPerformed(ActionEvent evt) {
            for (int i=0; i<=panelIndex; ++i) {
                if (isCanceled()) {
                    break;
                }
                panels.get(i).onOkAction();
            }
            setVisible(false);
        }
    }

    class WindowEventHandler extends WindowAdapter {
        @Override
        public void windowClosing(WindowEvent arg0) {
            new CancelAction().cancel();
        }
    }

    public static char[] toCharArray(String s) {
        if (s == null)
            return new char[0];
        else
            return s.toCharArray();
    }
    
    public static String string(String s) {
        if (s == null)
            return "";
        else
            return s;
    }
}
