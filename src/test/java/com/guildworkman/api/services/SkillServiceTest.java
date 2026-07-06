//package com.guildworkman.api.services;
//
//import com.guildworkman.api.dto.requests.AddSkillRequest;
//import com.guildworkman.api.dto.requests.RegistrationRequest;
//import com.guildworkman.api.dto.responses.AddSkillResponse;
//import com.guildworkman.api.dto.responses.SkilledWorkerRegistrationResponse;
//import com.guildworkman.api.data.models.Address;
//import com.guildworkman.api.data.constants.Category;
//import com.guildworkman.api.data.models.Skill;
//import com.guildworkman.api.data.repository.AddressRepository;
//import com.guildworkman.api.data.repository.SkillRepository;
//import com.guildworkman.api.data.repository.ClientRepository;
//import com.guildworkman.api.services.ServiceUtils.SkillService;
//import com.guildworkman.api.services.ServiceUtils.SkilledWorkerService;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.context.jdbc.Sql;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
//@SpringBootTest
//@Sql(scripts = {"/db/data.sql"})
//public class SkillServiceTest {
//    @Autowired
//    private SkilledWorkerService skilledWorkerService;
//    @Autowired
//    private ClientRepository userRepository;
//    @Autowired
//    private SkillRepository skillRepository;
//    @Autowired
//    private SkillService skillService;
//    @Autowired
//    private AddressRepository addressRepository;
//
//    @Test
//    public void addSkillTest() {
//
//        RegistrationRequest registrationRequest = new RegistrationRequest();
//        registrationRequest.setFirstName("Fitzgerald");
//        registrationRequest.setLastName("McDonald");
//        registrationRequest.setEmail("fitzgerald@gmail.com");
//        registrationRequest.setPassword("password");
//        registrationRequest.setUsername("Fitz");
//        registrationRequest.setPhoneNumber("1234567890");
//        registrationRequest.setHouseNumber("312");
//        registrationRequest.setStreet("Herbert Macaulay Way");
//        registrationRequest.setArea("Yaba");
//        SkilledWorkerRegistrationResponse response = skilledWorkerService.registerSkilledWorker(registrationRequest);
//        assertThat(skilledWorkerService.getNumberOfUsers()).isEqualTo(1L);
//        assertThat(response.getSkilledWorkerId()).isNotNull();
//
//        AddSkillRequest addSkillRequest = new AddSkillRequest();
//        addSkillRequest.setSkilledWorkerId(response.getSkilledWorkerId());
//        addSkillRequest.setCategory(Category.ELECTRICAL);
//        addSkillRequest.setSkillName("Electronics Electrician");
//        AddSkillResponse skillResponse = skillService.addSkill(addSkillRequest);
//        System.out.println("ID --> " +skillResponse.getSkilledWorkerId());
//        assertThat(skillService.findSkillFor(response.getSkilledWorkerId())).hasSize(1);
//        Skill skill = skillRepository.findSkillById(skillResponse.getSkillId());
//
//        assertThat(skill.getSkillName()).isEqualTo("Electronics Electrician");
//        assertThat(skill.getSkilledWorker().getId()).isEqualTo(response.getSkilledWorkerId());
//    }
//
//
//    @Test
//    public void addMoreThatOneSkillTest() {
//
//        RegistrationRequest registrationRequest = new RegistrationRequest();
//        registrationRequest.setFirstName("Fitzgerald");
//        registrationRequest.setLastName("McDonald");
//        registrationRequest.setEmail("fitzgerald@gmail.com");
//        registrationRequest.setUsername("Fitz");
//        registrationRequest.setPassword("password");
//        registrationRequest.setPhoneNumber("1234567890");
//        registrationRequest.setHouseNumber("312");
//        registrationRequest.setStreet("Herbert Macaulay Way");
//        registrationRequest.setArea("Yaba");
//        SkilledWorkerRegistrationResponse response = skilledWorkerService.registerSkilledWorker(registrationRequest);
//        assertThat(skilledWorkerService.getNumberOfUsers()).isEqualTo(1L);
//        assertThat(response).isNotNull();
//
//
//        AddSkillRequest addSkillRequest = new AddSkillRequest();
//        addSkillRequest.setSkilledWorkerId(response.getSkilledWorkerId());
//        addSkillRequest.setCategory(Category.ELECTRICAL);
//        addSkillRequest.setSkillName("Electronics Electrician");
//        AddSkillResponse skillResponse = skillService.addSkill(addSkillRequest);
//
//        AddSkillRequest addSkillRequest1 = new AddSkillRequest();
//        addSkillRequest1.setSkilledWorkerId(response.getSkilledWorkerId());
//        addSkillRequest1.setCategory(Category.CARPENTRY);
//        addSkillRequest1.setSkillName("Carpenter");
//        AddSkillResponse skillResponse1 = skillService.addSkill(addSkillRequest1);
//
//        var skills = skillRepository.findSkillBySkillWorkerId(skillResponse.getSkilledWorkerId());
//        assertThat(skillResponse1).isNotNull();
//        assertThat(skills.size()).isEqualTo(2L);
//        assertThat(skills.get(1).getSkillName()).isEqualTo("Carpenter");
//        assertThat(skills.get(0).getSkillName()).isEqualTo("Electronics Electrician");
//        assertThat(skills.get(1).getSkilledWorker().getId()).isEqualTo(response.getSkilledWorkerId());
//    }
//
//    @Test
//    public void editSkillTest() {
//
//    }
//
//}
