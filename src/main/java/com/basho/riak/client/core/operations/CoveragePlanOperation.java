/*
 * Copyright 2013-2015 Basho Technologies Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.basho.riak.client.core.operations;

import com.basho.riak.client.core.FutureOperation;
import com.basho.riak.client.core.RiakMessage;
import com.basho.riak.client.core.query.Namespace;
import com.basho.riak.client.core.util.HostAndPort;
import com.basho.riak.protobuf.RiakKvPB;
import com.basho.riak.protobuf.RiakMessageCodes;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

/**
 * An operation to retrieve a coverage plan from Riak.
 *
 * @author Sergey Galkin <sgalkin at basho dot com>
 */
public class CoveragePlanOperation extends FutureOperation<CoveragePlanOperation.Response, RiakKvPB.RpbCoverageResp, Namespace>
{
    private final RiakKvPB.RpbCoverageReq.Builder reqBuilder;
    private final Namespace namespace;

    private CoveragePlanOperation(AbstractBuilder builder)
    {
        this.reqBuilder = builder.reqBuilder;
        namespace = builder.namespace;
    }

    @Override
    protected Response convert(List<RiakKvPB.RpbCoverageResp> rawResponse)
    {
        Response r = new Response();
        for (RiakKvPB.RpbCoverageResp resp : rawResponse)
        {
            for (RiakKvPB.RpbCoverageEntry e: resp.getEntriesList())
            {
                final Response.CoverageEntry ce = new Response.CoverageEntry();
                ce.coverageContext = e.getCoverContext().toByteArray();
                ce.description = e.getKeyspaceDesc().toStringUtf8();
                ce.host = e.getIp().toStringUtf8();
                ce.port = e.getPort();

                if ("0.0.0.0".equals(ce.getHost()))
                {
                    LoggerFactory.getLogger(CoveragePlanOperation.class).error(
                            "CoveragePlanOperation returns at least one coverage entry: '{}' -- with IP address '0.0.0.0'.\n" +
                                "Execution will be failed due to the imposibility of using IP '0.0.0.0' " +
                                "for querying data from the remote Riak.",
                            ce.description);

                    throw new RuntimeException("CoveragePlanOperation returns at least one coverage entry with ip '0.0.0.0'.");
                }

                r.addEntry(ce);
            }
        }
        return r;
    }

    @Override
    protected RiakMessage createChannelMessage()
    {
        return new RiakMessage(RiakMessageCodes.MSG_CoverageReq, reqBuilder.build().toByteArray());
    }

    @Override
    protected RiakKvPB.RpbCoverageResp decode(RiakMessage rawMessage)
    {
        try
        {
            Operations.checkPBMessageType(rawMessage, RiakMessageCodes.MSG_CoverageResp);
            return RiakKvPB.RpbCoverageResp.parseFrom(rawMessage.getData());
        }
        catch (InvalidProtocolBufferException e)
        {
            throw new IllegalArgumentException("Invalid message received", e);
        }
    }

    @Override
    public Namespace getQueryInfo()
    {
        return namespace;
    }

    public static abstract class AbstractBuilder<R>
    {
        private final RiakKvPB.RpbCoverageReq.Builder reqBuilder = RiakKvPB.RpbCoverageReq.newBuilder();
        private Namespace namespace;

        public AbstractBuilder(Namespace ns)
        {
            if (ns == null)
            {
                throw new IllegalArgumentException("Namespace can not be null");
            }

            reqBuilder.setType(ByteString.copyFrom(ns.getBucketType().unsafeGetValue()));
            reqBuilder.setBucket(ByteString.copyFrom(ns.getBucketName().unsafeGetValue()));

            namespace = ns;
        }

        public AbstractBuilder<R> withMinPartitions(int minPartitions)
        {
            reqBuilder.setMinPartitions(minPartitions);
            return this;
        }

        public AbstractBuilder<R> withReplaceCoverageEntry(Response.CoverageEntry coverageEntry)
        {
            return withReplaceCoverageContext(coverageEntry.getCoverageContext());
        }

        public AbstractBuilder<R> withReplaceCoverageContext(byte[] coverageContext)
        {
            reqBuilder.setReplaceCover(ByteString.copyFrom(coverageContext));
            return this;
        }

        public AbstractBuilder<R> withUnavailableCoverageContext(Iterable<byte[]> coverageContext)
        {
            for(Iterator<byte[]> iterator=coverageContext.iterator(); iterator.hasNext();)
            {
                withUnavailableCoverageContext(iterator.next());
            }
            return this;
        }

        public AbstractBuilder<R> withUnavailableCoverageEntries(Iterable<Response.CoverageEntry> coverageEntries)
        {
            for (Response.CoverageEntry coverageEntry : coverageEntries)
            {
                withUnavailableCoverageContext(coverageEntry.getCoverageContext());
            }
            return this;
        }

        public AbstractBuilder<R> withUnavailableCoverageContext(byte[]...coverageContext)
        {
            for(byte[] cc: coverageContext)
            {
                reqBuilder.addUnavailableCover(ByteString.copyFrom(cc));
            }
            return this;
        }

        public CoveragePlanOperation buildOperation()
        {
            return new CoveragePlanOperation(this);
        }

        public abstract R build();

        public Namespace getNamespace()
        {
            return namespace;
        }
    }

    public static class Response implements Iterable<Response.CoverageEntry>
    {

        public static class CoverageEntry implements Serializable
        {
            private static final long serialVersionUID = 0;
            private String host;
            private int port;

            /**
             * Some human readable description of the keyspace covered
             */
            private String description;

            /**
             *  Opaque context to pass into 2I query
             */
            private byte[] coverageContext;

            public String getHost()
            {
                return host;
            }

            public int getPort()
            {
                return port;
            }

            public String getDescription()
            {
                return description;
            }

            public byte[] getCoverageContext()
            {
                return coverageContext;
            }

            @Override
            public boolean equals(Object o)
            {
                if (this == o) return true;
                if (!(o instanceof CoverageEntry)) return false;

                CoverageEntry that = (CoverageEntry) o;

                if (getPort() != that.getPort()) return false;
                if (!getHost().equals(that.getHost())) return false;
                return Arrays.equals(getCoverageContext(), that.getCoverageContext());

            }

            @Override
            public int hashCode()
            {
                int result = getHost().hashCode();
                result = 31 * result + getPort();
                result = 31 * result + Arrays.hashCode(getCoverageContext());
                return result;
            }

            @Override
            public String toString()
            {
                return "CoverageEntry{" +
                        "description='" + description + '\'' +
                        '}';
            }
        }

        private HashMap<HostAndPort, List<CoverageEntry>> perHostCoverage = new HashMap<HostAndPort, List<CoverageEntry>>();

        protected Response()
        {
        }

        protected Response(Response rhs)
        {
            this.perHostCoverage.putAll(rhs.perHostCoverage);
        }

        public Set<HostAndPort> hosts()
        {
            return perHostCoverage.keySet();
        }

        public List<CoverageEntry> hostEntries(HostAndPort host)
        {
            final List<CoverageEntry> lst = perHostCoverage.get(host);

            if(lst == null)
            {
                return Collections.emptyList();
            }

            return lst;
        }

        public List<CoverageEntry> hostEntries(String host, int port)
        {
            return hostEntries(HostAndPort.fromParts(host, port));
        }

        private static <T>  Iterator<T> emptyIterator()
        {
            return Collections.<T>emptyList().iterator();
        }

        @Override
        public Iterator<CoverageEntry> iterator()
        {
            final Iterator<List<CoverageEntry>> itor = perHostCoverage.values().iterator();

            return new Iterator<CoverageEntry>()
            {
                Iterator<CoverageEntry> subIterator = null;

                @Override
                public boolean hasNext()
                {
                    if(subIterator == null || !subIterator.hasNext())
                    {
                        if(itor.hasNext())
                        {
                            subIterator = itor.next().iterator();
                        }
                        else
                        {
                            subIterator = emptyIterator();
                            return false;
                        }
                    }

                    return subIterator.hasNext();
                }

                @Override
                public CoverageEntry next()
                {
                    if(!hasNext())
                    {
                        throw new NoSuchElementException();
                    }

                    assert subIterator != null;
                    return subIterator.next();
                }

                @Override
                public void remove()
                {
                    throw new UnsupportedOperationException();
                }
            };
        }

        private void addEntry(CoverageEntry coverageEntry)
        {
            final HostAndPort key = HostAndPort.fromParts(coverageEntry.getHost(), coverageEntry.getPort());
            List<CoverageEntry> lst =  perHostCoverage.get(key);
            if(lst == null)
            {
                lst = new LinkedList<CoverageEntry>();
                perHostCoverage.put(key, lst);
            }
            lst.add(coverageEntry);
        }
    }
}
