/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.query.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.teiid.adminapi.impl.DataPolicyMetadata;
import org.teiid.api.exception.query.QueryMetadataException;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.util.StringUtil;
import org.teiid.metadata.AbstractMetadataRecord;
import org.teiid.metadata.KeyRecord;
import org.teiid.metadata.MetadataStore;
import org.teiid.metadata.Procedure;
import org.teiid.metadata.Schema;
import org.teiid.metadata.Table;
import org.teiid.metadata.Table.Type;
import org.teiid.query.QueryPlugin;


/**
 * Aggregates the metadata from multiple stores.  
 */
public class CompositeMetadataStore extends MetadataStore {
	private static final long serialVersionUID = 6868525815774998010L;
	
	private volatile int oidId = 1;
	public static class RecordHolder {
		AbstractMetadataRecord record;
		Integer oid;
		public RecordHolder(AbstractMetadataRecord record, Integer oid) {
			this.record = record;
			this.oid = oid;
		}
		
		public Integer getOid() {
			return oid;
		}
		
		public AbstractMetadataRecord getRecord() {
			return record;
		}
	}
	private volatile TreeMap<String, RecordHolder> oids;

	public CompositeMetadataStore(MetadataStore metadataStore) {
		merge(metadataStore);
	}
	
	public CompositeMetadataStore(List<MetadataStore> metadataStores) {
		for (MetadataStore metadataStore : metadataStores) {
			merge(metadataStore);
		}
	}
	
	public Table findGroup(String fullName)	throws QueryMetadataException {
		int index = fullName.indexOf(TransformationMetadata.DELIMITER_STRING);
		if (index == -1) {
		     throw new QueryMetadataException(QueryPlugin.Event.TEIID30353, fullName+TransformationMetadata.NOT_EXISTS_MESSAGE);
		}			
		String schemaName = fullName.substring(0, index);
		Schema schema = getSchema(schemaName);
		if (schema == null ) {
	         throw new QueryMetadataException(QueryPlugin.Event.TEIID30352, fullName+TransformationMetadata.NOT_EXISTS_MESSAGE);			
		}
		Table result = schema.getTables().get(fullName.substring(index + 1));
		if (result == null) {
	         throw new QueryMetadataException(QueryPlugin.Event.TEIID30354, fullName+TransformationMetadata.NOT_EXISTS_MESSAGE);
		}
		return result;
	}
	
	/**
	 * TODO: this resolving mode allows partial matches of a full group name containing .
	 * @param partialGroupName
	 * @return
	 */
	public Collection<Table> getGroupsForPartialName(String partialGroupName) {
		List<Table> result = new LinkedList<Table>();
		for (Schema schema : getSchemas().values()) {
			for (Table t : schema.getTables().values()) {
				String name = t.getName();
				if (matchesPartialName(partialGroupName, name, schema)) {
					result.add(t);	
				}
			}
		}
		return result;
	}
	
	protected boolean matchesPartialName(String partialGroupName, String name, Schema schema) {
		if (!StringUtil.endsWithIgnoreCase(name, partialGroupName)) {
			return false;
		}
		int schemaMatch = partialGroupName.length() - name.length();
		if (schemaMatch > 0) {
			if (schemaMatch != schema.getName().length() + 1 
					|| !StringUtil.startsWithIgnoreCase(partialGroupName, schema.getName())
					|| partialGroupName.charAt(schemaMatch + 1) != '.') {
				return false;
			}
		} else if (schemaMatch < 0 && name.charAt(-schemaMatch - 1) != '.') {
			return false;
		}
		return true;
	}
	
	public Collection<Procedure> getStoredProcedure(String name) throws TeiidComponentException, QueryMetadataException {
		List<Procedure> result = new LinkedList<Procedure>();
		int index = name.indexOf(TransformationMetadata.DELIMITER_STRING);
		if (index > -1) {
			String schemaName = name.substring(0, index);
			Schema schema = getSchema(schemaName);
			if (schema == null ) {
		         throw new QueryMetadataException(QueryPlugin.Event.TEIID30352, name+TransformationMetadata.NOT_EXISTS_MESSAGE);			
			}			
			Procedure proc = schema.getProcedures().get(name.substring(index + 1));
			if (proc != null) {
				result.add(proc);
		        return result;
			}	
		}
		//assume it's a partial name
		for (Schema schema : getSchemas().values()) {
			for (Procedure p : schema.getProcedures().values()) {
				if (matchesPartialName(name, p.getName(), schema)) {
					result.add(p);	
				}
			}
		}
		return result;
	}

	/*
	 * The next method is a hold over from XML/UUID resolving and will perform poorly
	 */

	public Collection<Table> getXMLTempGroups(Table tableRecord) {
		ArrayList<Table> results = new ArrayList<Table>();
		String namePrefix = tableRecord.getName() + TransformationMetadata.DELIMITER_STRING;
		for (Table table : tableRecord.getParent().getTables().values()) {
			if (table.getTableType() == Type.XmlStagingTable && table.getName().startsWith(namePrefix)) {
				results.add(table);
			}
		}
		return results;
	}
	
	private void assignOids(Schema schema, TreeMap<String, RecordHolder> map) {
		addOid(schema, map);
		for (Table table : schema.getTables().values()) {
			addOid(table, map);
			addOids(table.getColumns(), map);
			addOids(table.getAllKeys(), map);
		}
		for (Procedure proc : schema.getProcedures().values()) {
			addOid(proc, map);
			addOids(proc.getParameters(), map);
			if (proc.getResultSet() != null) {
				addOids(proc.getResultSet().getColumns(), map);
			}
		}
		addOids(schema.getFunctions().values(), map);
	}
	
	private void addOid(AbstractMetadataRecord record, TreeMap<String, RecordHolder> map) {
		RecordHolder old = map.put(record.getUUID(), new RecordHolder(record, oidId++));
		if (old != null) {
			throw new AssertionError("duplicate uid " + record); //$NON-NLS-1$
		}
		if (record instanceof KeyRecord) {
			//pretend that each keyrecord contains its own columns
			oidId += ((KeyRecord)record).getColumns().size();
		}
	}
	
	private void addOids(Collection<? extends AbstractMetadataRecord> records, TreeMap<String, RecordHolder> map) {
		if (records == null) {
			return;
		}
		for (AbstractMetadataRecord record : records) {
			addOid(record, map);
		}
	}
	
	public Integer getOid(String record) {
		RecordHolder holder = getOids().get(record);
		if (holder == null) {
			return null;
		}
		return holder.oid;
	}
	
	public TreeMap<String, RecordHolder> getOids() {
		if (oids == null) {
			synchronized (this) {
				if (oids == null) {
					TreeMap<String, RecordHolder> map = new TreeMap<String, RecordHolder>(String.CASE_INSENSITIVE_ORDER);
					addOids(this.getDatatypes().values(), map);
					for (Schema s : getSchemaList()) {
						assignOids(s, map);
					}
					oids = map;
				}
			}
		}
		return oids;
	}
	
	public int getMaxOid() {
		getOids();
		return oidId;
	}
	
	public void processGrants(Map<String, DataPolicyMetadata> policies) {
		if (this.grants == null || policies == null) {
			return;
		}
		for (Grant grant : this.grants) {
			DataPolicyMetadata dpm = policies.get(grant.getRole());
			if (dpm != null) {
				dpm.addPermission(grant.getPermission());
			}
		}
	}
	
}
