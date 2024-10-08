/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Oracle and/or its affiliates. All rights reserved.
 *
 * Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 *
 * Contributor(s):
 *
 * Portions Copyrighted 2010 Sun Microsystems, Inc.
 */

package org.netbeans.modules.keyring.kde;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.netbeans.spi.keyring.KeyringProvider;

/**
 * JNA wrapper for certain functions from KDE Wallet API.
 * @author psychollek, ynov
 */
public class KWalletProvider implements KeyringProvider {

    private static final Logger logger = Logger.getLogger(KWalletProvider.class.getName());
    private char[] handler = "0".toCharArray();
    private boolean timeoutHappened = false;
    private final char[] defaultLocalWallet = "kdewallet".toCharArray();

    @Override
    public boolean enabled(){
        if (Boolean.getBoolean("netbeans.keyring.no.native")) {
            logger.fine("native keyring integration disabled");
            return false;
        }
        CommandResult result = runCommand("isEnabled");
        if(new String(result.retVal).equals("true")) {        
            return updateHandler();
        }                   
        return false;
    }

    @Override
    public char[] read(String key){
        if (updateHandler()){
            CommandResult result = runCommand("readPassword", handler, getApplicationName(), key.toCharArray(), getApplicationName());
            if (result.exitCode != 0){
                warning("read action returned not 0 exitCode");
            }
            return result.retVal.length > 0 ? result.retVal : null;
        }
        return null;
        //throw new KwalletException("read");
    }

    @Override
    public void save(String key, char[] password, String description){
        //description is forgoten ! kdewallet dosen't have any facility to store
        //it by default and I don't want to do it by adding new fields to kwallet
        if (updateHandler()){
            CommandResult result = runCommand("writePassword", handler , getApplicationName()
                    , key.toCharArray(), password , getApplicationName());
            if (result.exitCode != 0 || new String(result.retVal).equals("-1")) {
                warning("save action failed");
            }
        }
        //throw new KwalletException("save");
    }

    @Override
    public void delete(String key){
        if (updateHandler()){
            CommandResult result = runCommand("removeEntry" ,handler,
            getApplicationName() , key.toCharArray() , getApplicationName());
             if (result.exitCode != 0  || new String(result.retVal).equals("-1")) {
                warning("delete action failed");
            }
        }
        //throw new KwalletException("delete");
    }

    private boolean updateHandler(){
        if(timeoutHappened) {
            return false;
        }
        handler = new String(handler).isEmpty() ? "0".toCharArray() : handler;
        CommandResult result = runCommand("isOpen",handler);          
        if(new String(result.retVal).equals("true")){
            return true;
        }
        char[] localWallet = defaultLocalWallet;
        result = runCommand("localWallet");                      
        if(result.exitCode == 0) {                    
            localWallet = result.retVal;
        }
            
        if(new String(localWallet).contains(".service")) {            
            //Temporary workaround for the bug in kdelibs/kdeui/util/kwallet.cpp
            //The bug was fixed http://svn.reviewboard.kde.org/r/5885/diff/
            //but many people currently use buggy kwallet
            return false;
        }
        result = runCommand("open", localWallet , "0".toCharArray(), getApplicationName());
        if(result.exitCode == 2) { 
            warning("time out happened while accessing KWallet");
            //don't try to open KWallet anymore until bug https://bugs.kde.org/show_bug.cgi?id=259229 is fixed
            timeoutHappened = true;
            return false;
        }      
        if(result.exitCode != 0 || new String(result.retVal).equals("-1")) {
            warning("failed to access KWallet");
            return false;
        }         
        handler = result.retVal;
        return true;
    }
          
    

    private CommandResult runCommand(String command,char[]... commandArgs) {
        CommandResult result = null;
        for (int i : new int[] {6, 5, 0}) {
            result = runCommandKdeVersion(i, command, commandArgs);
            if (result.exitCode == 0 && !result.errVal.equals("Service 'org.kde.kwalletd" + (i != 0 ? i : "") + "' does not exist.")) {
                break;
            }
        }
        return result;
    }

    private CommandResult runCommandKdeVersion(int kdeVersion, String command, char[]... commandArgs) {
        String[] argv = new String[commandArgs.length+4];
        argv[0] = "qdbus";
        argv[1] = "org.kde.kwalletd" + (kdeVersion != 0 ? kdeVersion : "");
        argv[2] = "/modules/kwalletd" + (kdeVersion != 0 ? kdeVersion : "");
        argv[3] = "org.kde.KWallet."+command;
        for (int i = 0; i < commandArgs.length; i++) {
            //unfortunately I cannot pass char[] to the exec in any way - so this poses a security issue with passwords in String() !
            //TODO: find a way to avoid changing char[] into String
            argv[i+4] = new String(commandArgs[i]);
        }
        Runtime rt = Runtime.getRuntime();
        String retVal = "";
        String errVal = "";
        int exitCode = 0;
        try {
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "executing {0}", Arrays.toString(argv));
            }
            Process pr = rt.exec(argv);
            
            try (BufferedReader input = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while((line = input.readLine()) != null) {
                    if (!retVal.isEmpty()){
                        retVal = retVal.concat("\n");
                    }
                    retVal = retVal.concat(line);
                }            
            }
            try (BufferedReader input = new BufferedReader(new InputStreamReader(pr.getErrorStream(), StandardCharsets.UTF_8))) {

                String line;
                while((line = input.readLine()) != null) {
                    if (!errVal.isEmpty()){
                        errVal = errVal.concat("\n");
                    }
                    errVal = errVal.concat(line);
                }
            }

            exitCode = pr.waitFor();
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "application exit with code {0} for commandString: {1}; errVal: {2}",
                            new Object[]{exitCode, Arrays.toString(argv), errVal});
            }       
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.log(Level.FINE,
                    "exception thrown while invoking the command \""+Arrays.toString(argv)+"\"",
                    ex);
        } catch (IOException ex) {
            logger.log(Level.FINE,
                    "exception thrown while invoking the command \""+Arrays.toString(argv)+"\"",
                    ex);
        }
        return new CommandResult(exitCode, retVal.trim().toCharArray(), errVal.trim());
    }    

    private char[] getApplicationName(){
        return "JOSM".toCharArray(); // NOI18N
    }

    private void warning(String descr) {
        logger.log(Level.WARNING, "Something went wrong: {0}", descr);
    }      
  
    private static class CommandResult {
        private final int exitCode;
        private final char[] retVal;
        private final String errVal;

        public CommandResult(int exitCode, char[] retVal, String errVal) {
            this.exitCode = exitCode;
            this.retVal = retVal;
            this.errVal = errVal;
        }                        
    }

}
