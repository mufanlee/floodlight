/**
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.hasupport.topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.hasupport.IHAWorker;
import net.floodlightcontroller.hasupport.IHAWorkerService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery.LDUpdate;
import net.floodlightcontroller.topology.ITopologyListener;
import net.floodlightcontroller.topology.ITopologyService;

/**
 * TopoHAWorker
 * 
 * This is the HAWorker class that is used to push updates into the 
 * queue using the publishHook method. Also, subscribe to updates from
 * the syncDB, and display/process them if needed using subscribeHook.
 * 
 * The assemble update function is used to convert the updates into a JSON
 * for easier storage (i.e. as a string which can then be processed using 
 * Jackson) and storing them into the syncDB. JSON relational mapping is
 * performed in the SyncAdapter class, because the data in the updates are 
 * relational in nature and this improves write efficiency.
 * 
 * Forward flow:
 * 
 *  HAWorker         |             FilterQueue                  |     SyncAdapter
 * publishHook()     -> enqueueForward() -> dequeueForward()    ->  packJSON() -> syncDB.
 * 
 * Reverse Flow:
 * 
 *  HAWorker         |             FilterQueue                  |     SyncAdapter
 * subscribeHook()   ->   subscribe()                           ->  unpackJSON()
 * subscribeHook()	 <-  dequeueReverse() <- enqueueReverse()   <-  unpackJSON()  <- syncDB
 * 
 * @author Bhargav Srinivasan, Om Kale
 *
 */

public class TopoHAWorker implements IHAWorker, IFloodlightModule, ITopologyListener {
	protected static Logger logger = LoggerFactory.getLogger(TopoHAWorker.class);
	protected static ITopologyService toposerv;
	protected static IFloodlightProviderService floodlightProvider;
	protected static IHAWorkerService haworker;
	
	List<String> synTopoUList = Collections.synchronizedList(new ArrayList<String>());
	private static final TopoFilterQueue myTopoFilterQueue = new TopoFilterQueue(); 
	
	public TopoHAWorker(){};
	
	public TopoFilterQueue getFilterQ(){
		return myTopoFilterQueue;
	}
	
	/**
	 * This method is used to assemble the Topo Updates into
	 * a JSON string using JSON Jackson API
	 * @return JSON string
	 */
	
	@Override
	public List<String> assembleUpdate() {
		List<String> jsonInString = new LinkedList<String>();
		TopoUtils parser = new TopoUtils();
		
		String preprocess = new String (synTopoUList.toString());
		// Flatten the updates and strip off leading [
		
		if(preprocess.startsWith("[")){
			preprocess = preprocess.substring(1, preprocess.length());
		}
		
		String chunk = new String(preprocess.toString());
		
		if(! preprocess.startsWith("]") ) {
			jsonInString = parser.parseChunk(chunk);
		}

		//logger.debug("[Assemble Update] JSON String: {}", new Object[] {jsonInString});
		return jsonInString;
	}

    /**
     * This method is called in order to start pushing updates 
     * into the syncDB
     * @return boolean value indicating success or failure
     */
	
	public boolean publishHook() {
		try{
			synchronized (synTopoUList){
				//logger.debug("[Publish] Printing Updates {}: ",new Object[]{synTopoUList});
				List<String> updates = assembleUpdate();
				for(String update : updates){
					myTopoFilterQueue.enqueueForward(update);
				}
				synTopoUList.clear();
				myTopoFilterQueue.dequeueForward();
			}
			return true;
		} catch (Exception e){
			logger.debug("[TopoHAWorker] An exception occoured!");
			return false;
		}
	}

	/**
	 * This method is used to subscribe to updates from the syncDB, and 
	 * stay in sync. Can be used to unpack the updates, if needed.
	 * @return boolean value indicating success or failure
	 */

	public boolean subscribeHook(String controllerID) {
		try {
//			List<String> updates = new ArrayList<String>();
			myTopoFilterQueue.subscribe(controllerID);
			myTopoFilterQueue.dequeueReverse();
//			logger.info("[Subscribe] TopoUpdates...");
//			for (String update: updates) {
//				logger.debug("Update: {}", new Object[]{update.toString()});
//			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public void topologyChanged(List<LDUpdate> linkUpdates) {
		synchronized(synTopoUList){
			//synLDUList.clear();
			for (LDUpdate update: linkUpdates){	
				synTopoUList.add(update.toString());
			}
		}
		
	}
	
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		return null;
	}
	
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		return null;
	}
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
    	Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
    	l.add(IHAWorkerService.class);
		return l;
	}
	
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		toposerv = context.getServiceImpl(ITopologyService.class);
		haworker = context.getServiceImpl(IHAWorkerService.class);
		//logger.info("TopoHAWorker is init...");
	}
	
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		logger = LoggerFactory.getLogger(TopoHAWorker.class);
		toposerv.addListener(this);
		haworker.registerService("TopoHAWorker",this);
		logger.info("TopoHAWorker is starting...");
		
		return;
	}	
	
}
