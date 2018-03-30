/*
 * Copyright 2018 Emmanouil Gkatziouras
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gkatzioura.maven.cloud.abs;

import java.io.File;
import java.util.List;

import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.SessionListener;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.proxy.ProxyInfoProvider;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gkatzioura.maven.cloud.listener.SessionListenerContainer;
import com.gkatzioura.maven.cloud.listener.SessionListenerContainerImpl;
import com.gkatzioura.maven.cloud.listener.TransferListenerContainer;
import com.gkatzioura.maven.cloud.listener.TransferListenerContainerImpl;
import com.gkatzioura.maven.cloud.transfer.TransferProgress;
import com.gkatzioura.maven.cloud.transfer.TransferProgressImpl;

public class AzureStorageWagon implements Wagon {

    private static final boolean SUPPORTS_DIRECTORY_COPY = true;

    private int connectionTimeOut = 0;
    private int readConnectionTimeOut = 0;

    private Repository repository = null;
    private AzureStorageRepository azureStorageRepository;

    private final AccountResolver accountResolver;
    private final ContainerResolver containerResolver;

    private final SessionListenerContainer sessionListenerContainer;
    private final TransferListenerContainer transferListenerContainer;

    private boolean interactive;

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureStorageWagon.class);

    public AzureStorageWagon() {
        this.accountResolver = new AccountResolver();
        this.containerResolver = new ContainerResolver();
        this.sessionListenerContainer = new SessionListenerContainerImpl(this);
        this.transferListenerContainer = new TransferListenerContainerImpl(this);
    }

    @Override
    public void get(String resourceName, File destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {

        Resource resource = new Resource(resourceName);
        transferListenerContainer.fireTransferInitiated(resource, TransferEvent.REQUEST_GET);
        transferListenerContainer.fireTransferStarted(resource, TransferEvent.REQUEST_GET);

        try {
            azureStorageRepository.copy(resourceName,destination);
            transferListenerContainer.fireTransferCompleted(resource,TransferEvent.REQUEST_GET);
        } catch (Exception e) {
            transferListenerContainer.fireTransferError(resource,TransferEvent.REQUEST_GET,e);
            throw e;
        }

    }

    @Override
    public boolean getIfNewer(String s, File file, long l) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        if(azureStorageRepository.newResourceAvailable(s, l)) {
            get(s,file);
            return true;
        }

        return false;
    }

    @Override
    public void put(File file, String resourceName) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {

        Resource resource = new Resource(resourceName);

        LOGGER.debug("Uploading file {} to {}",file.getAbsolutePath(),resourceName);

        transferListenerContainer.fireTransferInitiated(resource,TransferEvent.REQUEST_PUT);
        transferListenerContainer.fireTransferStarted(resource,TransferEvent.REQUEST_PUT);

        TransferProgress transferProgress = new TransferProgressImpl(resource, TransferEvent.REQUEST_PUT, transferListenerContainer);

        try {
            azureStorageRepository.put(file, resourceName);
            transferListenerContainer.fireTransferCompleted(resource, TransferEvent.REQUEST_PUT);
        } catch (TransferFailedException e) {
            transferListenerContainer.fireTransferError(resource,TransferEvent.REQUEST_PUT,e);
            throw new TransferFailedException("Faild to transfer artifact",e);
        }
    }

    @Override
    public void putDirectory(File source, String destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        File[] files = source.listFiles();
        if (files != null) {
            for (File f : files) {
                put(f, destination + "/" + f.getName());
            }
        }
    }

    @Override
    public boolean resourceExists(String s) throws TransferFailedException, AuthorizationException {
        return azureStorageRepository.exists(s);
    }

    @Override
    public List<String> getFileList(String s) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        return azureStorageRepository.list(s);
    }

    @Override
    public boolean supportsDirectoryCopy() {
        return SUPPORTS_DIRECTORY_COPY;
    }

    @Override
    public Repository getRepository() {
        return repository;
    }

    @Override
    public void connect(Repository repository) throws ConnectionException, AuthenticationException {
        connect(repository,null,(ProxyInfoProvider) null);
    }

    @Override
    public void connect(Repository repository, ProxyInfo proxyInfo) throws ConnectionException, AuthenticationException {
        connect(repository,null,proxyInfo);
    }

    @Override
    public void connect(Repository repository, ProxyInfoProvider proxyInfoProvider) throws ConnectionException, AuthenticationException {
        connect(repository, null, proxyInfoProvider);
    }

    @Override
    public void connect(Repository repository, AuthenticationInfo authenticationInfo) throws ConnectionException, AuthenticationException {
        connect(repository, authenticationInfo, (ProxyInfoProvider) null);
    }

    @Override
    public void connect(Repository repository, AuthenticationInfo authenticationInfo, ProxyInfo proxyInfo) throws ConnectionException, AuthenticationException {
        connect(repository, authenticationInfo, p->{if((p == null) || (proxyInfo == null) || p.equalsIgnoreCase(proxyInfo.getType())) return proxyInfo;  else return null;});
    }

    @Override
    public void connect(Repository repository, AuthenticationInfo authenticationInfo, ProxyInfoProvider proxyInfoProvider) throws ConnectionException, AuthenticationException {

        this.repository = repository;
        this.sessionListenerContainer.fireSessionOpening();

        try {

            final String account = accountResolver.resolve(repository);
            final String container = containerResolver.resolve(repository);

            LOGGER.debug("Opening connection for account {} and container {}",account,container);

            azureStorageRepository = new AzureStorageRepository(account,container);
            azureStorageRepository.connect(authenticationInfo);
            sessionListenerContainer.fireSessionLoggedIn();
            sessionListenerContainer.fireSessionOpened();
        } catch (Exception e) {
            this.sessionListenerContainer.fireSessionConnectionRefused();
            throw e;
        }
    }

    @Override
    public void openConnection() throws ConnectionException, AuthenticationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void disconnect() throws ConnectionException {

        azureStorageRepository.disconnect();
    }

    @Override
    public void setTimeout(int i) {
        this.connectionTimeOut = i;
    }

    @Override
    public int getTimeout() {
        return connectionTimeOut;
    }

    @Override
    public void setReadTimeout(int i) {
        readConnectionTimeOut = i;
    }

    @Override
    public int getReadTimeout() {
        return readConnectionTimeOut;
    }

    @Override
    public void addSessionListener(SessionListener sessionListener) {
        sessionListenerContainer.addSessionListener(sessionListener);
    }

    @Override
    public void removeSessionListener(SessionListener sessionListener) {
        sessionListenerContainer.removeSessionListener(sessionListener);
    }

    @Override
    public boolean hasSessionListener(SessionListener sessionListener) {
        return sessionListenerContainer.hasSessionListener(sessionListener);
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        transferListenerContainer.addTransferListener(transferListener);
    }

    @Override
    public void removeTransferListener(TransferListener transferListener) {
        transferListenerContainer.removeTransferListener(transferListener);
    }

    @Override
    public boolean hasTransferListener(TransferListener transferListener) {
        return transferListenerContainer.hasTransferListener(transferListener);
    }

    @Override
    public boolean isInteractive() {
        return interactive;
    }

    @Override
    public void setInteractive(boolean b) {
        interactive = b;
    }
}
