/*******************************************************************************
 * NGSEP - Next Generation Sequencing Experience Platform
 * Copyright 2016 Jorge Duitama
 *
 * This file is part of NGSEP.
 *
 *     NGSEP is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     NGSEP is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with NGSEP.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package ngsep.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.logging.Logger;

import ngsep.alignments.LongReadsAligner;
import ngsep.alignments.ReadAlignment;
import ngsep.sequences.DNAMaskedSequence;
import ngsep.sequences.FMIndex;
import ngsep.sequences.FMIndexUngappedSearchHit;
import ngsep.sequences.KmerHitsCluster;
import ngsep.sequences.KmersExtractor;

/**
 * @author Jorge Duitama
 * @author Juan Camilo Bojaca
 * @author David Guevara
 */
public class GraphBuilderFMIndex implements GraphBuilder {
	private Logger log = Logger.getLogger(GraphBuilderFMIndex.class.getName());
	private final static int TALLY_DISTANCE = 200;
	private final static int SUFFIX_FRACTION = 40;
	
	private static final int TIMEOUT_SECONDS = 30;

	private int kmerLength;
	private int kmerOffset;
	private int minKmerPercentage;
	private int numThreads;
	
	private LongReadsAligner aligner = new LongReadsAligner();
	
	private static int idxDebug = -1;
	
	

	public GraphBuilderFMIndex(int kmerLength, int kmerOffset, int minKmerPercentage, int numThreads) {
		this.kmerLength = kmerLength;
		this.kmerOffset = kmerOffset;
		this.minKmerPercentage = minKmerPercentage;
		this.numThreads = numThreads;
	}
	
	/**
	 * @return the log
	 */
	public Logger getLog() {
		return log;
	}
	
	/**
	 * @param log the log to set
	 */
	public void setLog(Logger log) {
		this.log = log;
	}

	@Override
	public AssemblyGraph buildAssemblyGraph(List<CharSequence> sequences) {
		
		AssemblyGraph graph = new AssemblyGraph(sequences);
		log.info("Created graph vertices. Edges: "+graph.getEdges().size());
		// Create FM-Index
		FMIndex fmIndex = new FMIndex();
		fmIndex.loadUnnamedSequences(sequences, TALLY_DISTANCE, SUFFIX_FRACTION);
		
		log.info("Created FM-Index");
		
		ThreadPoolExecutor pool = new ThreadPoolExecutor(numThreads, numThreads, TIMEOUT_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
	
		for (int seqId = 0; seqId < sequences.size(); seqId++) {
			CharSequence seq = sequences.get(seqId);
			if(numThreads==1) {
				processSequence(graph, fmIndex, seqId, seq);
				if ((seqId+1)%100==0) log.info("Processed "+(seqId+1) +" sequences. Number of edges: "+graph.getEdges().size()+ " Embedded: "+graph.getEmbeddedCount());
				continue;
			}
			Runnable task = new ProcessSequenceTask(this, graph, fmIndex, seqId, seq);
			pool.execute(task);
		}
		pool.shutdown();
		try {
			pool.awaitTermination(2*sequences.size(), TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
    	if(!pool.isShutdown()) {
			throw new RuntimeException("The ThreadPoolExecutor was not shutdown after an await Termination call");
		}
		log.info("Built graph. Edges: "+graph.getEdges().size()+" Embedded: "+graph.getEmbeddedCount()+" Prunning embedded sequences");
		graph.pruneEmbeddedSequences();
		log.info("Prunned graph. Edges: "+graph.getEdges().size());
		
		return graph;
	}

	void processSequence(AssemblyGraph graph, FMIndex fmIndex, int seqId, CharSequence seq) {
		updateGraph(graph, seqId, seq, false, fmIndex);
		CharSequence complement = DNAMaskedSequence.getReverseComplement(seq);
		updateGraph(graph, seqId, complement, true, fmIndex);
		synchronized (graph) {
			graph.filterEdgesAndEmbedded (seqId);
		}
		if(seqId == idxDebug) System.out.println("Edges start: "+graph.getEdges(graph.getVertex(seqId, true)).size()+" edges end: "+graph.getEdges(graph.getVertex(seqId, false)).size()+" Embedded: "+graph.getEmbeddedBySequenceId(seqId));
	}


	private void updateGraph(AssemblyGraph graph, int querySequenceId, CharSequence query, boolean queryRC, FMIndex fmIndex) {
		Map<Integer,CharSequence> kmersMap = KmersExtractor.extractKmersAsMap(query, kmerLength, kmerOffset, true, true, true);
		//Search kmers using the FM index
		if(kmersMap.size()==0) return;
		int kmersCount=0;
		double averageHits = 0;
		List<FMIndexUngappedSearchHit> kmerHitsList = new ArrayList<>();
		for (int start:kmersMap.keySet()) {
			String kmer = kmersMap.get(start).toString();
			//List<FMIndexUngappedSearchHit> kmerHits=fmIndex.exactSearch(kmer,0,querySequenceId-1);
			List<FMIndexUngappedSearchHit> kmerHits=fmIndex.exactSearch(kmer);
			int numHits = kmerHits.size();
			//Remove from count hit to self
			if(!queryRC) numHits--;
			if(numHits==0) continue;
			boolean added = false;
			//if(querySequenceId==idxDebug) System.out.println("Query: "+querySequenceId+" complement: "+queryRC+" Found "+numHits+" hits for kmer at: "+start);
			for(FMIndexUngappedSearchHit hit:kmerHits) {
				//if(querySequenceId==52) System.out.println("Kmer start: "+hit.getStart()+" Next alignment: "+aln.getSequenceIndex()+": "+aln.getFirst()+"-"+aln.getLast()+" rc: "+aln.isNegativeStrand());
				if(hit.getSequenceIdx()>=querySequenceId) continue;
				hit.setQueryIdx(start);
				kmerHitsList.add(hit);
				added = true;
			}
			if(added) {
				kmersCount++;
				averageHits+=numHits;
			}
		}
		if(kmersCount==0) return;
		averageHits/=kmersCount;
		if(averageHits<1) averageHits = 1;
		
		//Filter repetitive kmer hits
		if(querySequenceId==idxDebug) System.out.println("Query: "+querySequenceId+" complement: "+queryRC+" kmers: "+kmersCount+" Average hits "+averageHits);
		
		// Cluster hits by target region
		int minKmers = (int) (0.5*minKmerPercentage*kmersCount/100);
		List<KmerHitsCluster> clusteredKmerAlns = clusterKmerHits(querySequenceId, query, kmerHitsList, Math.max(10, minKmers));
		if(querySequenceId==idxDebug) System.out.println("Query id: "+querySequenceId+" RC: "+queryRC+" kmers: "+kmersCount+" Clusters: "+clusteredKmerAlns.size());
		
		//Process clusters
		Collections.sort(clusteredKmerAlns, (o1,o2)-> o2.getNumDifferentKmers()-o1.getNumDifferentKmers());
		for (int i=0;i<clusteredKmerAlns.size() && i<10;i++) {
			KmerHitsCluster cluster = clusteredKmerAlns.get(i);
			cluster.summarize(averageHits, kmersCount);
			double pct = 100.0*cluster.getProportionKmers();
			if(querySequenceId==idxDebug) System.out.println("Processing cluster. QueryStart: "+cluster.getQueryStart()+" query end: "+cluster.getQueryEnd()+" Subject: "+cluster.getSequenceIdx()+" first: "+cluster.getFirst()+" last: "+cluster.getLast()+" plain count: "+cluster.getNumDifferentKmers()+" weighted count: "+cluster.getWeightedCount()+" pct: "+pct+" coverage: "+cluster.getQueryCoverage());
			if(pct<minKmerPercentage) break;
			synchronized (graph) {
				processAlignment(graph, querySequenceId, queryRC, cluster);
			}
		}
	}

	private List<KmerHitsCluster> clusterKmerHits(int querySequenceId, CharSequence query, List<FMIndexUngappedSearchHit> kmerHits, int minKmers) {
		List<KmerHitsCluster> clusters = new ArrayList<>();
		Map<Integer,List<FMIndexUngappedSearchHit>> hitsByTargetSequence = new HashMap<>();
		for(FMIndexUngappedSearchHit kmerHit: kmerHits) {
			int targetSequenceId = kmerHit.getSequenceIdx();
			List<FMIndexUngappedSearchHit> targetHits = hitsByTargetSequence.computeIfAbsent(targetSequenceId, k-> new ArrayList<FMIndexUngappedSearchHit>());
			targetHits.add(kmerHit);
		}
		
		for(int targetIdx:hitsByTargetSequence.keySet()) {
			List<FMIndexUngappedSearchHit> targetHits = hitsByTargetSequence.get(targetIdx);
			// TODO: choose better min coverage
			if(targetHits.size()>=minKmers) clusters.addAll(LongReadsAligner.clusterSequenceKmerAlns(querySequenceId, query, targetHits, 0));
		}
		return clusters;
	}
	
	
	public void printTargetHits(List<FMIndexUngappedSearchHit> targetHits) {
		for(FMIndexUngappedSearchHit hit:targetHits) {
			System.out.println(hit.getQueryIdx()+" "+hit.getSequenceIdx()+":"+hit.getStart());
		}
		
	}

	private void processAlignment(AssemblyGraph graph, int querySequenceId, boolean queryRC, KmerHitsCluster cluster) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int targetSeqIdx = cluster.getSequenceIdx();
		int targetLength = graph.getSequenceLength(targetSeqIdx);
		//Zero based limits
		int firstTarget = cluster.getFirst();
		int lastTarget = cluster.getLast();
		//System.out.println("Processing cluster. Query: "+querySequenceId+" length: "+queryLength+ " target: "+cluster.getSequenceIdx()+" length: "+targetLength);
		if(firstTarget>0 && lastTarget<=targetLength) {
			addEmbedded(graph, querySequenceId, queryRC, cluster);
		} else if (firstTarget>0) {
			addQueryAfterTargetEdge(graph, querySequenceId, queryRC, cluster);
		} else if (lastTarget<=targetLength) {
			addQueryBeforeTargetEdge(graph, querySequenceId, queryRC, cluster);
		} else {
			ReadAlignment aln = aligner.alignRead(graph.getSequence(targetSeqIdx), graph.getSequence(querySequenceId), 0, targetLength, "Subject", 0.5);
			if(aln!=null) {
				int firstQueryMatch = 0;
				int newFirst = targetLength;
				int newLast = -1;
				for(int i=0;i<queryLength;i++) {
					int subjectPos = aln.getReferencePosition(i);
					if(subjectPos>0) {
						firstQueryMatch = i;
						newFirst = subjectPos-1-i;
						break;
					}
				}
				for(int i=queryLength-1;i>=firstQueryMatch;i--) {
					int subjectPos = aln.getReferencePosition(i);
					if(subjectPos>0) {
						newLast= subjectPos-1+(queryLength-1-i);
						break;
					}
				}
				if(newFirst==targetLength || newLast==-1) return;
				cluster.setFirst(newFirst);
				cluster.setLast(newLast);
				if(newFirst>0 && newLast<=targetLength) {
					addEmbedded(graph, querySequenceId, queryRC, cluster);
				} else if (newFirst>0) {
					addQueryAfterTargetEdge(graph, querySequenceId, queryRC, cluster);
				} else if (newLast<=targetLength) {
					addQueryBeforeTargetEdge(graph, querySequenceId, queryRC, cluster);
				}
			}
		}
	}
	private void addEmbedded(AssemblyGraph graph, int querySequenceId, boolean queryRC, KmerHitsCluster cluster) {
		int firstTarget = cluster.getFirst()-1;
		int targetSeqIdx = cluster.getSequenceIdx();
		double pct = 100.0*cluster.getProportionKmers();
		double queryCoverage = cluster.getQueryCoverage();
		//TODO: improve rules for embedded sequences
		if(pct>=2*minKmerPercentage && queryCoverage>=0.5) {
			AssemblyEmbedded embeddedEvent = new AssemblyEmbedded(querySequenceId, graph.getSequence(querySequenceId), queryRC, targetSeqIdx, firstTarget);
			embeddedEvent.setEvidence(cluster);
			graph.addEmbedded(embeddedEvent);
			if (querySequenceId==idxDebug) System.out.println("Query: "+querySequenceId+" embedded in "+targetSeqIdx);
		}
	}
	private void addQueryAfterTargetEdge(AssemblyGraph graph, int querySequenceId, boolean queryRC, KmerHitsCluster cluster) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int targetSeqIdx = cluster.getSequenceIdx();
		int targetLength = graph.getSequenceLength(targetSeqIdx);
		int queryRegionLength = cluster.getQueryEnd()-cluster.getQueryStart();
		int startTarget = cluster.getFirst()-1;
		AssemblyVertex vertexTarget = graph.getVertex(targetSeqIdx, false);
		AssemblyVertex vertexQuery;
		if(queryRC) {
			vertexQuery = graph.getVertex(querySequenceId, false); 
		} else {
			vertexQuery = graph.getVertex(querySequenceId, true);
		}
		int overlap = targetLength-startTarget;
		if (queryRegionLength > 0.5*overlap) {
			int cost = targetLength + queryLength - overlap;
			AssemblyEdge edge = new AssemblyEdge(vertexTarget, vertexQuery, cost, overlap);
			edge.setEvidence(cluster);
			graph.addEdge(edge);
		}
		//System.out.println("Edge between target: "+targetSeqIdx+" and query "+querySequenceId+" overlap: "+overlap+" weight: "+weight);
	}
	private void addQueryBeforeTargetEdge(AssemblyGraph graph, int querySequenceId, boolean queryRC,
			KmerHitsCluster cluster) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int targetSeqIdx = cluster.getSequenceIdx();
		int targetLength = graph.getSequenceLength(targetSeqIdx);
		int queryRegionLength = cluster.getQueryEnd()-cluster.getQueryStart();
		int endTarget = cluster.getLast();
		AssemblyVertex vertexTarget = graph.getVertex(targetSeqIdx, true);
		AssemblyVertex vertexQuery;
		if(queryRC) {
			vertexQuery = graph.getVertex(querySequenceId, true); 
		} else {
			vertexQuery = graph.getVertex(querySequenceId, false);
		}
		int overlap = endTarget;
		if (queryRegionLength > 0.5*overlap) {
			int cost = targetLength + queryLength -overlap;
			AssemblyEdge edge = new AssemblyEdge(vertexQuery, vertexTarget, cost, overlap);
			edge.setEvidence(cluster);
			graph.addEdge(edge);
		}
		// System.out.println("Edge between query: "+querySequenceId+" and target "+targetSeqIdx+" overlap: "+overlap+" weight: "+weight);
	}
}
class ProcessSequenceTask implements Runnable {
	private GraphBuilderFMIndex parent;
	private AssemblyGraph graph;
	private FMIndex fmIndex;
	private int sequenceId;
	private CharSequence sequence;
	
	
	
	
	public ProcessSequenceTask(GraphBuilderFMIndex parent, AssemblyGraph graph, FMIndex fmIndex, int sequenceId, CharSequence sequence) {
		super();
		this.parent = parent;
		this.graph = graph;
		this.fmIndex = fmIndex;
		this.sequenceId = sequenceId;
		this.sequence = sequence;
	}
	@Override
	public void run() {
		// TODO Auto-generated method stub
		parent.processSequence(graph, fmIndex, sequenceId, sequence);
		if ((sequenceId+1)%100==0) parent.getLog().info("Processed "+(sequenceId+1) +" sequences. Number of edges: "+graph.getNumEdges()+ " Embedded: "+graph.getEmbeddedCount());
	}
	
	
}