package one.bca.SpringBatch_AbsensiKaryawan.configuration;

import one.bca.SpringBatch_AbsensiKaryawan.listener.CustomCreateOutputCSVSkipListener;
import one.bca.SpringBatch_AbsensiKaryawan.mapper.ReaderAbsensiOutputRowMapper;
import one.bca.SpringBatch_AbsensiKaryawan.model.AbsensiOutputCSV;
import one.bca.SpringBatch_AbsensiKaryawan.partitionoer.PartitionerAbsensiOutput;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.PagingQueryProvider;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.transform.BeanWrapperFieldExtractor;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Configuration
public class BatchConfiguration {
    private final DataSourceTransactionManager transactionManager;
    private final JobRepository jobRepository;
    String lastMonthFirstDayString;
    public static String[] namesofExtractorAbsensiOutputCSV = new String[] { "karyawanId", "namaDepan", "namaBelakang", "jumlahCutiTersisa",
            "jumlahCutiTelahDiambil", "totalDurasiLembur", "totalKehadiran" };

    public BatchConfiguration(DataSourceTransactionManager transactionManager, JobRepository jobRepository, RowMapper<AbsensiOutputCSV> readerAbsensiOutputRowMapper) {
        this.transactionManager = transactionManager;
        this.jobRepository = jobRepository;

        // Get the current date
        LocalDate currentDate = LocalDate.now();

        // Subtract one month from the current date and set the day to the first day of the month
        LocalDate lastMonthFirstDay = currentDate.minusMonths(1).withDayOfMonth(1);

        // Format the last month's first day as a string in the format "YYYY-MM-DD"
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        lastMonthFirstDayString = lastMonthFirstDay.format(formatter);

        // Output the string representation of the last month's first day
        System.out.println("Last month's first day         : " + lastMonthFirstDayString);

        //dummy date ke 2024-06-01
        lastMonthFirstDayString = "2024-06-01";
        System.out.println("Last month's first day [dummy] : " + lastMonthFirstDayString);
    }

    public Job jobStart() throws Exception {
        return new JobBuilder("jobPengolahanAbsensiKaryawan", jobRepository)
                .start(masterCreateOutputCSVAbsensi())
                .build();
    }

    public Step masterCreateOutputCSVAbsensi() throws Exception {
        return new StepBuilder("masterCreateOutputCSVAbsensi", jobRepository)
                .partitioner("partitionerOutputCSVAbsensi", partitionerAbsensiOutputCSV())
                .partitionHandler(partitionAbsensiOutputCSVHandler())
                .build();
    }

    @Bean
    public Partitioner partitionerAbsensiOutputCSV() {
        return new PartitionerAbsensiOutput();
    }

    @Bean
    public PartitionHandler partitionAbsensiOutputCSVHandler() throws Exception {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(new SimpleAsyncTaskExecutor());
        handler.setStep(stepCreateOutputCSVAbsensi());
        handler.setGridSize(3); //number of partition. max value 26 karena alfabet dari 'A' to 'Z' hanya ada 26
        return handler;
    }

    public Step stepCreateOutputCSVAbsensi() throws Exception {
        return new StepBuilder("stepCreateOutputCSVAbsensi", jobRepository)
                .<AbsensiOutputCSV, AbsensiOutputCSV>chunk(2, transactionManager)
                .reader(readBahanAbsensiOutputCSV())
                .processor(new ItemProcessor<AbsensiOutputCSV, AbsensiOutputCSV>() {
                    @Override
                    public AbsensiOutputCSV process(AbsensiOutputCSV absensiOutputCSV) throws Exception {

//                        System.out.println("absensiOutputCSV : " + absensiOutputCSV.toString());
                        if (absensiOutputCSV.getKaryawanId().equals(4L) ||
                                absensiOutputCSV.getKaryawanId().equals(1L) ||
                                absensiOutputCSV.getKaryawanId().equals(6L) ||
                                absensiOutputCSV.getKaryawanId().equals(8L)) {
                            try {
                                throw new Exception("Pengkondisian Error di Processor");
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                        return absensiOutputCSV;
                    }
                })
                .writer(writeAbsensiOutputCSV(null))
                .faultTolerant()
                .skip(Exception.class)
//                .skipLimit(1) // bikin gagal
                .skipLimit(2)   // tetap sukses karena .skipLimit ini per thread dari partitionernya
                .listener(new CustomCreateOutputCSVSkipListener())
                .build();
    }


    @Bean
    @StepScope
    public JdbcPagingItemReader<AbsensiOutputCSV> readBahanAbsensiOutputCSV() throws Exception {
        return new JdbcPagingItemReaderBuilder<AbsensiOutputCSV>()
                .dataSource(transactionManager.getDataSource())
                .queryProvider(queryProviderOfReadBahanAbsensiOutputCSV())
                .rowMapper(new ReaderAbsensiOutputRowMapper())
                .pageSize(2) // Set page size as needed
                .name("pagingItemReader")
                .build();
    }

    public PagingQueryProvider queryProviderOfReadBahanAbsensiOutputCSV() throws Exception {
//        System.out.println("querry provider started by-" + Thread.currentThread().getName());
        StepContext stepContext = StepSynchronizationManager.getContext();
        ExecutionContext executionContext = stepContext.getStepExecution().getExecutionContext();
        String fromChar = executionContext.getString("fromChar");
        String toChar = executionContext.getString("toChar");
//        System.out.println("fromChar = " + fromChar + " | toChar = " + toChar);

        PostgresPagingQueryProvider factory = new PostgresPagingQueryProvider();

        // SELECT k.karyawan_id AS "k.karyawan_id" -> di set alias sebagai "k.karyawan_id" karena karyawan_id dimiliki dua table.
        // jika hanya SELECT karyawan_id, Postgresql tidak bisa tahu jika hanya find dari karyawan_id saja karena ambiguous
        // jika hanya SELECT k.karyawan_id, ResultSet tidak bisa sort dari k.karywan_id karena tahu resultnya "karyawan_id"
        // maka dari itu di set alias ke "k.karyawan_id" agar tetap cocok dengan sintaks sql, dan ResultSet tahu kolomnya

        factory.setSelectClause("SELECT k.karyawan_id AS \"k.karyawan_id\", k.nama_depan, k.nama_belakang, k.jumlah_cuti_tersisa, k.jumlah_cuti_telah_diambil, ab.total_durasi_lembur, ab.total_kehadiran");
        factory.setFromClause("FROM karyawan k, absensi_bulanan ab");
        factory.setWhereClause("WHERE k.karyawan_id = ab.karyawan_id " +
                "AND ab.bulan_dan_tahun_absensi = '" + lastMonthFirstDayString + "' " +
                "AND (SUBSTRING(k.nama_depan, 1, 1) BETWEEN '"+ fromChar + "' AND '" + toChar + "') ");
        Map<String, Order> sortKeys = new HashMap<>();
        sortKeys.put("k.karyawan_id", Order.ASCENDING);
        factory.setSortKeys(sortKeys);
        factory.init(Objects.requireNonNull(transactionManager.getDataSource()));
//        System.out.println("querry provider ended");
        return factory;
    }

    @Bean
    @StepScope
    public ItemWriter<AbsensiOutputCSV> writeAbsensiOutputCSV(@Value("#{stepExecutionContext['fileName']}") String fileName){

//        System.out.println("ItemWriter started");

        FlatFileItemWriter<AbsensiOutputCSV> itemWriter = new FlatFileItemWriter<>();
        itemWriter.setResource(new FileSystemResource(fileName));

        DelimitedLineAggregator<AbsensiOutputCSV> aggregator = new DelimitedLineAggregator<AbsensiOutputCSV>();
        aggregator.setDelimiter(",");

        BeanWrapperFieldExtractor<AbsensiOutputCSV> fieldExtractor = new BeanWrapperFieldExtractor<AbsensiOutputCSV>();
        fieldExtractor.setNames(namesofExtractorAbsensiOutputCSV);

        aggregator.setFieldExtractor(fieldExtractor);
        itemWriter.setLineAggregator(aggregator);

        itemWriter.setHeaderCallback(writer -> {
                writer.write("karyawan_id, nama_depan, nama_belakang, jumlah_cuti_tersisa, " +
                        "jumlah_cuti_telah_diambil, total_durasi_lembur, total_kehadiran");
        });
        itemWriter.open(new ExecutionContext());
        return itemWriter;
    }
}
