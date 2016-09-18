package io.cattle.platform.simple.allocator.dao.impl;

import static io.cattle.platform.core.model.tables.AgentTable.*;
import static io.cattle.platform.core.model.tables.HostTable.*;
import static io.cattle.platform.core.model.tables.StoragePoolHostMapTable.*;
import static io.cattle.platform.core.model.tables.StoragePoolTable.*;
import static io.cattle.platform.core.model.tables.InstanceHostMapTable.*;
import static io.cattle.platform.core.model.tables.InstanceTable.*;

import io.cattle.platform.allocator.service.AllocationCandidate;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.simple.allocator.AllocationCandidateCallback;
import io.cattle.platform.simple.allocator.dao.QueryOptions;
import io.cattle.platform.simple.allocator.dao.SimpleAllocatorDao;

import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;

import org.jooq.Condition;
import org.jooq.Cursor;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Record4;
import org.jooq.impl.DSL;

import com.netflix.config.DynamicBooleanProperty;

public class SimpleAllocatorDaoImpl extends AbstractJooqDao implements SimpleAllocatorDao {

    private static final DynamicBooleanProperty SPREAD = ArchaiusUtil.getBoolean("simple.allocator.spread");

    ObjectManager objectManager;

    @Override
    public Iterator<AllocationCandidate> iteratorPools(List<Long> volumes, QueryOptions options) {
        return iteratorHosts(volumes, options, false, null);
    }

    @Override
    public Iterator<AllocationCandidate> iteratorHosts(List<Long> volumes, QueryOptions options, AllocationCandidateCallback callback) {
        return iteratorHosts(volumes, options, true, callback);
    }
    public static final Field<Integer> INSTANCE_NUM_PER_HOST=HOST.ID.count().as("instance_num");
    protected Iterator<AllocationCandidate> iteratorHosts(List<Long> volumes, QueryOptions options, boolean hosts,
            AllocationCandidateCallback callback) {
        final Cursor<Record2<Long, Long>> cursor;
        if(hosts){
        	Table<Record2<Long, Integer>> availableHosts=create().
    		select(HOST.ID,INSTANCE_NUM_PER_HOST).
    		from(HOST)
    		.leftOuterJoin(AGENT)
            	.on(AGENT.ID.eq(HOST.AGENT_ID))
    		.leftOuterJoin(INSTANCE_HOST_MAP)
        		.on(INSTANCE_HOST_MAP.HOST_ID.eq(HOST.ID))
        	.leftOuterJoin(INSTANCE)
        		.on(INSTANCE_HOST_MAP.INSTANCE_ID.eq(INSTANCE.ID).and(INSTANCE_HOST_MAP.STATE.eq(CommonStatesConstants.ACTIVE).or(INSTANCE_HOST_MAP.STATE.eq(CommonStatesConstants.INACTIVE).and(INSTANCE.STATE.eq(InstanceConstants.STATE_STARTING)))))
        	 .where(HOST.STATE.in(CommonStatesConstants.ACTIVE, CommonStatesConstants.UPDATING_ACTIVE))
        	 .and(AGENT.ID.isNull().or(AGENT.STATE.eq(CommonStatesConstants.ACTIVE)))
        	 .and(getHostQueryOptionCondition(options))
        	.groupBy(HOST.ID)
            .asTable("host");
        	cursor= create()
                    .select(HOST.ID, STORAGE_POOL.ID)
                    .from(availableHosts)
                    .leftOuterJoin(STORAGE_POOL_HOST_MAP)
                        .on(STORAGE_POOL_HOST_MAP.HOST_ID.eq(HOST.ID)
                            .and(STORAGE_POOL_HOST_MAP.REMOVED.isNull()))
                    .join(STORAGE_POOL)
                        .on(STORAGE_POOL.ID.eq(STORAGE_POOL_HOST_MAP.STORAGE_POOL_ID))
                    .where(STORAGE_POOL.STATE.eq(CommonStatesConstants.ACTIVE))
                        .and(getStorageQueryOptionCondition(options))
                    .orderBy(INSTANCE_NUM_PER_HOST.asc())
                    .fetchLazy();
        }else{
        	cursor = create()
                    .select(HOST.ID, STORAGE_POOL.ID)
                    .from(HOST)
                    .leftOuterJoin(STORAGE_POOL_HOST_MAP)
                        .on(STORAGE_POOL_HOST_MAP.HOST_ID.eq(HOST.ID)
                            .and(STORAGE_POOL_HOST_MAP.REMOVED.isNull()))
                    .join(STORAGE_POOL)
                        .on(STORAGE_POOL.ID.eq(STORAGE_POOL_HOST_MAP.STORAGE_POOL_ID))
                    .leftOuterJoin(AGENT)
                        .on(AGENT.ID.eq(HOST.AGENT_ID))
                    .where(
                        AGENT.ID.isNull().or(AGENT.STATE.eq(CommonStatesConstants.ACTIVE))
                        .and(HOST.STATE.in(CommonStatesConstants.ACTIVE, CommonStatesConstants.UPDATING_ACTIVE))
                        .and(STORAGE_POOL.STATE.eq(CommonStatesConstants.ACTIVE))
                        .and(getQueryOptionCondition(options)))
                    .orderBy((SPREAD.get() ? HOST.COMPUTE_FREE.desc() : HOST.COMPUTE_FREE.asc()), HOST.ID.asc())
                    .fetchLazy();

        }
        	

        return new AllocationCandidateIterator(objectManager, cursor, volumes, hosts, callback);
    }

    protected Condition getQueryOptionCondition(QueryOptions options) {
        Condition condition = null;

        if ( options.getHosts().size() > 0 ) {
            condition = append(condition, HOST.ID.in(options.getHosts()));
        }

        if ( options.getCompute() != null ) {
            condition = append(condition, HOST.COMPUTE_FREE.ge(options.getCompute()));
        }

        if ( options.getKind() != null ) {
            condition = append(condition,
                    HOST.KIND.eq(options.getKind()).and(STORAGE_POOL.KIND.eq(options.getKind())));
        }

        if (options.getAccountId() != null) {
            condition = append(condition, HOST.ACCOUNT_ID.eq(options.getAccountId()));
        }

        return condition == null ? DSL.trueCondition() : condition;
    }
    protected Condition getHostQueryOptionCondition(QueryOptions options) {
        Condition condition = null;

        if ( options.getHosts().size() > 0 ) {
            condition = append(condition, HOST.ID.in(options.getHosts()));
        }

        if ( options.getCompute() != null ) {
            condition = append(condition, HOST.COMPUTE_FREE.ge(options.getCompute()));
        }

        if ( options.getKind() != null ) {
            condition = append(condition,
                    HOST.KIND.eq(options.getKind()));
        }

        if (options.getAccountId() != null) {
            condition = append(condition, HOST.ACCOUNT_ID.eq(options.getAccountId()));
        }

        return condition == null ? DSL.trueCondition() : condition;
    }
    protected Condition getStorageQueryOptionCondition(QueryOptions options) {
        Condition condition = null;
        if ( options.getKind() != null ) {
            condition = append(condition,
            		STORAGE_POOL.KIND.eq(options.getKind()));
        }
        return condition == null ? DSL.trueCondition() : condition;
    }


    protected Condition append(Condition base, Condition next) {
        if ( base == null ) {
            return next;
        } else {
            return base.and(next);
        }
    }

    public ObjectManager getObjectManager() {
        return objectManager;
    }

    @Inject
    public void setObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

}
